use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{Receiver, RecvTimeoutError};
use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::json;

use crate::config::{Collector, Config};
use crate::parse::Entry;
use crate::Item;

/// 单次 HTTP 请求超时
const HTTP_TIMEOUT_MS: u64 = 10_000;
/// 聚合统计的输出间隔，避免每条日志都打印
const REPORT_INTERVAL: Duration = Duration::from_secs(30);

/**
 * 推送循环：按采集器各自攒批，满足「条数达到 batchSize」或「距上次推送超过 flushIntervalMs」
 * 就发一批。推送失败按 500ms/1s/2s 退避重试，超过次数后丢弃该批并告警
 * （服务端长时间不可用时，不能让队列无限增长把内存撑爆）。
 */
pub fn run(cfg: &Config, rx: Receiver<Item>, stop: Arc<AtomicBool>, src_ip: &str) {
    let timeout = Duration::from_millis(cfg.flush_interval_ms.max(50));
    let mut batches: HashMap<usize, Vec<Entry>> = HashMap::new();
    let mut queued: usize = 0;
    let mut sent_total: u64 = 0;
    let mut last_flush = Instant::now();
    let mut last_report = Instant::now();
    let mut last_drop_warn = Instant::now() - Duration::from_secs(60);

    loop {
        match rx.recv_timeout(timeout) {
            Ok(item) => {
                if queued >= cfg.queue_max {
                    // 队列已满：丢新日志并告警（限频，避免刷屏）
                    if last_drop_warn.elapsed() > Duration::from_secs(10) {
                        eprintln!(
                            "[xtlog-client] 队列已满（{} 条），服务端可能长时间不可用，正在丢弃新日志",
                            queued
                        );
                        last_drop_warn = Instant::now();
                    }
                    continue;
                }
                batches.entry(item.idx).or_default().push(item.entry);
                queued += 1;
            }
            Err(RecvTimeoutError::Timeout) => {
                if stop.load(Ordering::Relaxed) {
                    break;
                }
            }
            Err(RecvTimeoutError::Disconnected) => break,
        }

        // 1) 攒够一批就立即发
        let ready: Vec<usize> = batches
            .iter()
            .filter(|(_, v)| v.len() >= cfg.batch_size)
            .map(|(k, _)| *k)
            .collect();
        for idx in ready {
            if let Some(v) = batches.get_mut(&idx) {
                let batch = std::mem::take(v);
                queued = queued.saturating_sub(batch.len());
                if send_batch(cfg, idx, &batch, src_ip) {
                    sent_total += batch.len() as u64;
                }
                last_flush = Instant::now();
            }
        }

        // 2) 定时 flush：低流量时保证日志也能及时到达
        if last_flush.elapsed() >= timeout {
            let mut keys: Vec<usize> = batches.keys().copied().collect();
            keys.sort_unstable();
            for idx in keys {
                if let Some(v) = batches.get_mut(&idx) {
                    if !v.is_empty() {
                        let batch = std::mem::take(v);
                        queued = queued.saturating_sub(batch.len());
                        if send_batch(cfg, idx, &batch, src_ip) {
                            sent_total += batch.len() as u64;
                        }
                    }
                }
            }
            last_flush = Instant::now();
        }

        // 3) 周期性汇总，便于观察是否真的在推送
        if last_report.elapsed() >= REPORT_INTERVAL {
            if sent_total > 0 {
                println!(
                    "[xtlog-client] 已推送 {} 条日志（当前队列 {} 条）",
                    sent_total, queued
                );
            }
            last_report = Instant::now();
        }
    }

    // 退出前：把通道里剩下的捞干净再发一次，尽量不丢最后一批
    while let Ok(item) = rx.try_recv() {
        batches.entry(item.idx).or_default().push(item.entry);
    }
    let mut keys: Vec<usize> = batches.keys().copied().collect();
    keys.sort_unstable();
    for idx in keys {
        if let Some(v) = batches.get_mut(&idx) {
            let batch = std::mem::take(v);
            if !batch.is_empty() && send_batch(cfg, idx, &batch, src_ip) {
                sent_total += batch.len() as u64;
            }
        }
    }
    if sent_total > 0 {
        println!("[xtlog-client] 退出，累计推送 {} 条", sent_total);
    }
}

fn send_batch(cfg: &Config, idx: usize, batch: &[Entry], src_ip: &str) -> bool {
    let c = match cfg.collectors.get(idx) {
        Some(c) => c,
        None => return false,
    };
    let body = build_payload(c, batch, src_ip);
    let url = format!("{}/api/logs", cfg.server.trim_end_matches('/'));

    let mut delay = 500u64;
    for attempt in 0..=cfg.retry {
        match crate::net::post(&url, &body, HTTP_TIMEOUT_MS) {
            Ok((status, resp)) => {
                // 服务端对 token 无效等错误也是 HTTP 200，错误放在响应体的 error 字段里
                if resp.contains("\"error\"") {
                    eprintln!(
                        "[xtlog-client] 采集器#{}{} 服务端拒绝：{}（请检查 token 是否正确）",
                        idx,
                        c.name_tag(),
                        resp
                    );
                    return false;
                }
                if (200..300).contains(&status) {
                    if resp.contains("encodingWarning") {
                        eprintln!(
                            "[xtlog-client] 采集器#{}{} 服务端提示编码异常：{}",
                            idx,
                            c.name_tag(),
                            resp
                        );
                    }
                    return true;
                }
                eprintln!(
                    "[xtlog-client] 采集器#{}{} 推送失败：HTTP {} {}",
                    idx,
                    c.name_tag(),
                    status,
                    first_n(&resp, 200)
                );
            }
            Err(e) => {
                eprintln!(
                    "[xtlog-client] 采集器#{}{} 推送异常：{}",
                    idx,
                    c.name_tag(),
                    e
                );
            }
        }
        if attempt < cfg.retry {
            std::thread::sleep(Duration::from_millis(delay));
            delay = (delay * 2).min(8000);
        }
    }
    eprintln!(
        "[xtlog-client] 采集器#{}{} 连续 {} 次推送失败，丢弃该批 {} 条日志",
        idx,
        c.name_tag(),
        cfg.retry + 1,
        batch.len()
    );
    false
}

/// 组装请求体：{ token, appId, logs: [ {level, logger, thread, message, timestamp, srcIp} ] }
fn build_payload(c: &Collector, batch: &[Entry], src_ip: &str) -> String {
    let logs: Vec<serde_json::Value> = batch
        .iter()
        .map(|e| {
            let mut m = serde_json::Map::new();
            m.insert("level".into(), json!(e.level));
            m.insert("logger".into(), json!(e.logger));
            m.insert("thread".into(), json!(e.thread));
            m.insert("message".into(), json!(e.message));
            if let Some(ts) = e.ts_ms {
                m.insert("timestamp".into(), json!(ts));
            }
            if !src_ip.is_empty() {
                m.insert("srcIp".into(), json!(src_ip));
            }
            serde_json::Value::Object(m)
        })
        .collect();

    let mut root = serde_json::Map::new();
    root.insert("token".into(), json!(c.token));
    if let Some(id) = c.app_id {
        root.insert("appId".into(), json!(id));
    }
    root.insert("logs".into(), json!(logs));
    serde_json::to_string(&serde_json::Value::Object(root)).unwrap_or_default()
}

fn first_n(s: &str, n: usize) -> String {
    if s.len() > n {
        format!("{}...", &s[..n])
    } else {
        s.to_string()
    }
}
