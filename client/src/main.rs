mod config;
mod host;
mod net;
mod parse;
mod sender;
mod source;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

/// 采集到的一条日志，带上它属于哪个采集器（下标对应 config.collectors）
pub struct Item {
    pub idx: usize,
    pub entry: parse::Entry,
}

const VERSION: &str = env!("CARGO_PKG_VERSION");

fn print_help() {
    println!(
        "xtlog-client {} —— xtlog 客户端采集推送器\n\
         \n\
         用法：\n\
         \u{20} xtlog-client --config=config.json\n\
         \u{20} xtlog-client -c config.json        （--config 的简写）\n\
         \u{20} xtlog-client                       （默认读 ./config.json）\n\
         \n\
         参数：\n\
         \u{20} --config, -c <路径>   指定配置文件（默认 ./config.json）\n\
         \u{20} --help, -h            显示本帮助\n\
         \u{20} --version, -V         显示版本\n\
         \n\
         配置格式见 config.example.json：server 填 xtlog 服务地址，\n\
         collectors 数组里每项是一个应用（token + appId + file/cmd 采集源）。\n\
         \n\
         停止：Ctrl+C 或 SIGTERM，会先停采集、杀掉拉起的命令进程，再把剩余日志推完。",
        VERSION
    );
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let mut cfg_path = String::from("config.json");

    let mut i = 1usize;
    while i < args.len() {
        let a = args[i].as_str();
        if a == "--help" || a == "-h" {
            print_help();
            return;
        } else if a == "--version" || a == "-V" {
            println!("xtlog-client {}", VERSION);
            return;
        } else if let Some(v) = a.strip_prefix("--config=") {
            cfg_path = v.to_string();
        } else if a == "--config" || a == "-c" {
            if i + 1 < args.len() {
                cfg_path = args[i + 1].clone();
                i += 1;
            } else {
                eprintln!("--config 后面缺少配置文件路径");
                std::process::exit(2);
            }
        } else {
            eprintln!("未知参数：{}（用 --help 查看用法）", a);
            std::process::exit(2);
        }
        i += 1;
    }

    let cfg = match config::Config::load(&cfg_path) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("[xtlog-client] {}", e);
            std::process::exit(2);
        }
    };

    // 本机 IP：优先用配置里写死的；否则探测「去往服务端」的出口 IP
    let (srv_host, srv_port) = net::split_url(&cfg.server)
        .map(|(h, p, _)| (h, p))
        .unwrap_or_else(|_| (String::new(), 80));
    let src_ip = match cfg.src_ip.as_deref().map(|s| s.trim()) {
        Some(s) if !s.is_empty() => s.to_string(),
        _ => host::detect_local_ip(&srv_host, srv_port).unwrap_or_default(),
    };

    println!("[xtlog-client] v{} 启动", VERSION);
    println!("[xtlog-client] 配置文件：{}", cfg_path);
    println!("[xtlog-client] 服务地址：{}", cfg.server);
    println!(
        "[xtlog-client] 本机 IP ：{}",
        if src_ip.is_empty() {
            "未探测到（界面不显示 IP）".to_string()
        } else {
            src_ip.clone()
        }
    );
    println!("[xtlog-client] 采集器：{} 个", cfg.collectors.len());
    for (idx, c) in cfg.collectors.iter().enumerate() {
        let detail = if c.is_file() {
            format!("文件 {} 个，路径 {:?}", c.paths.len(), c.paths)
        } else {
            format!("命令 {}", c.command.as_deref().unwrap_or(""))
        };
        println!(
            "[xtlog-client]   #{} {} {}",
            idx,
            if c.is_file() { "file" } else { "cmd" },
            detail
        );
    }

    let stop = Arc::new(AtomicBool::new(false));
    let registry = source::ProcRegistry::new();
    let offsets = source::OffsetStore::new(cfg.state_file.clone());
    match offsets.path_display() {
        Some(p) => println!(
            "[xtlog-client] 状态文件：{}（记录文件采集偏移，仅 file 采集器使用）",
            p
        ),
        None => println!(
            "[xtlog-client] 状态文件：未配置 —— 进程重启后从日志文件末尾重新开始，停机期间写入的日志会丢"
        ),
    }

    // 优雅退出：Ctrl+C / SIGTERM 时停采集、杀掉拉起的命令进程、把剩余日志推完再退出
    let s_stop = stop.clone();
    let s_reg = registry.clone();
    let s_off = offsets.clone();
    if let Err(e) = ctrlc::set_handler(move || {
        if s_stop.swap(true, Ordering::Relaxed) {
            return; // 已在处理中
        }
        eprintln!("\n[xtlog-client] 收到退出信号，正在停止采集…");
        s_reg.kill_all();
        thread::sleep(Duration::from_millis(1500)); // 留时间给最后的批量推送
        s_off.flush();
        std::process::exit(0);
    }) {
        eprintln!("[xtlog-client] 注册退出信号处理失败：{}", e);
    }

    // 启动采集器
    let (tx, rx) = mpsc::channel::<Item>();
    for (idx, c) in cfg.collectors.iter().enumerate() {
        let tx2 = tx.clone();
        let stop2 = stop.clone();
        if c.is_file() {
            let c2 = c.clone();
            let off2 = offsets.clone();
            thread::spawn(move || {
                source::run_file_collector(&c2, idx, tx2, stop2, off2);
            });
        } else {
            let c2 = c.clone();
            let reg2 = registry.clone();
            thread::spawn(move || {
                source::run_cmd_collector(&c2, idx, tx2, stop2, reg2);
            });
        }
    }
    drop(tx); // 主线程的发送端不再需要

    // 文件偏移定期落盘（配了 stateFile 才有效）
    let off_t = offsets.clone();
    let stop_t = stop.clone();
    thread::spawn(move || {
        while !stop_t.load(Ordering::Relaxed) {
            thread::sleep(Duration::from_secs(3));
            off_t.flush();
        }
        off_t.flush();
    });

    println!("[xtlog-client] 已开始采集，Ctrl+C 停止");
    sender::run(&cfg, rx, stop.clone(), &src_ip);
    offsets.flush();
}
