/**
 * 日志行解析：与 xtlog-server 的 LogTextIngestor 保持一致的行为，
 * 这样「服务端自己采集文件」和「客户端采集后推送」两条路得到的结果一致。
 *
 * 负责三件事：
 *  1. 多行合并：把 Java 异常堆栈这类续行并进上一条日志（遇到「新条目」特征才切分）；
 *  2. 从行首解析时间戳，作为日志发生时间（服务端据此决定落在哪个月份表）；
 *  3. 推断级别 / logger / 线程名。
 */

use chrono::{Datelike, Local, TimeZone};

const LEVEL_WORDS: [&str; 11] = [
    "TRACE", "DEBUG", "INFO", "NOTICE", "WARN", "WARNING", "ERROR", "ERR", "FATAL", "CRITICAL",
    "SEVERE",
];

/// 缓冲安全上限：超过就强制输出，避免「永远没有新条目特征」的输出把内存撑爆
const MAX_BUFFER_BYTES: usize = 256 * 1024;

/// 一条待推送的日志
#[derive(Debug, Clone)]
pub struct Entry {
    pub level: String,
    pub logger: String,
    pub thread: String,
    pub message: String,
    /// 日志发生时间（毫秒时间戳）；解析不到行首时间时为 None，由服务端记接收时间
    pub ts_ms: Option<i64>,
}

impl Entry {
    /// 把一条完整的文本块（可能含续行）解析成结构化日志
    pub fn from_text(text: &str) -> Entry {
        let first = first_line(text);
        Entry {
            level: guess_level(first),
            logger: guess_logger(first),
            thread: guess_thread(first),
            message: text.to_string(),
            ts_ms: parse_log_time_ms(first),
        }
    }
}

/// 流式行的合并器：喂进一行，吐出已完整的日志块
pub struct Merger {
    buf: String,
}

impl Merger {
    pub fn new() -> Merger {
        Merger { buf: String::new() }
    }

    /// 返回 Some(text) 表示这一行触发了上一条的结束（text 是需要送走的上一条）
    pub fn push(&mut self, line: &str) -> Option<String> {
        let line = strip_cr(line);
        let mut done: Option<String> = None;
        if is_new_entry(line) {
            if !self.buf.is_empty() {
                done = Some(self.buf.clone());
                self.buf.clear();
            }
            self.buf.push_str(line);
        } else if self.buf.is_empty() {
            self.buf.push_str(line);
        } else {
            self.buf.push('\n');
            self.buf.push_str(line);
        }
        if self.buf.len() > MAX_BUFFER_BYTES {
            // 兜底强制输出：先吐出缓冲里的，再把刚才那行当成新缓冲
            let forced = self.buf.clone();
            self.buf.clear();
            return Some(forced);
        }
        done
    }

    /// 退出前把残留缓冲刷出，避免最后一条多行日志丢尾
    pub fn flush(&mut self) -> Option<String> {
        if self.buf.is_empty() {
            None
        } else {
            let s = self.buf.clone();
            self.buf.clear();
            Some(s)
        }
    }
}

/// 是否为一条新日志的起始行：行内/行首有时间戳，或首词就是级别词
pub fn is_new_entry(line: &str) -> bool {
    if line.is_empty() {
        return false;
    }
    if has_timestamp(line) {
        return true;
    }
    let head = match line.find(' ') {
        Some(sp) if sp > 0 => &line[..sp],
        _ => line,
    };
    LEVEL_WORDS.contains(&head.to_uppercase().as_str())
}

fn first_line(text: &str) -> &str {
    match text.find('\n') {
        Some(nl) => &text[..nl],
        None => text,
    }
}

fn strip_cr(line: &str) -> &str {
    if line.ends_with('\r') {
        &line[..line.len() - 1]
    } else {
        line
    }
}

/// 按 Java 的 stripBrackets：去掉首尾的方括号/圆括号与尾冒号，再转大写
fn strip_brackets(token: &str) -> String {
    let b = token.as_bytes();
    let mut s = 0usize;
    let mut e = b.len();
    while s < e && (b[s] == b'[' || b[s] == b'(') {
        s += 1;
    }
    while e > s && (b[e - 1] == b']' || b[e - 1] == b')' || b[e - 1] == b':') {
        e -= 1;
    }
    String::from_utf8_lossy(&b[s..e]).to_uppercase()
}

fn guess_level(first: &str) -> String {
    for (i, p) in first.split_whitespace().enumerate() {
        if i >= 8 {
            break;
        }
        let u = strip_brackets(p);
        if LEVEL_WORDS.contains(&u.as_str()) {
            // FATAL / SEVERE 归一到 ERROR，与服务端一致
            if u == "FATAL" || u == "SEVERE" {
                return "ERROR".to_string();
            }
            return u;
        }
    }
    let up = first.to_uppercase();
    if up.contains("FATAL") {
        return "ERROR".into();
    }
    if up.contains("ERROR") {
        return "ERROR".into();
    }
    if up.contains("WARN") {
        return "WARN".into();
    }
    if up.contains("DEBUG") {
        return "DEBUG".into();
    }
    if up.contains("TRACE") {
        return "TRACE".into();
    }
    "INFO".into()
}

/// 跳过行首时间戳与级别词，优先取形如 com.demo.Foo 的类名，否则取第一个有效 token
fn guess_logger(first: &str) -> String {
    let end = ts_end(first);
    let rest = if end > 0 { &first[end..] } else { first }.trim();
    let mut fallback: Option<&str> = None;
    for p in rest.split_whitespace() {
        if p.is_empty() {
            continue;
        }
        if LEVEL_WORDS.contains(&strip_brackets(p).as_str()) {
            continue;
        }
        if fallback.is_none() {
            fallback = Some(p);
        }
        if p.find('.').map_or(false, |i| i > 0) {
            return truncate(p, 200);
        }
    }
    fallback.map_or(String::new(), |f| truncate(f, 200))
}

/// 额外补的：取形如 [http-nio-8080-exec-1] / (main) 这样的线程名（服务端 FILE/CMD 采集不解析线程）
fn guess_thread(first: &str) -> String {
    let end = ts_end(first);
    let rest = if end > 0 { &first[end..] } else { first }.trim();
    for p in rest.split_whitespace().take(8) {
        let b = p.as_bytes();
        if b.len() >= 2
            && ((b[0] == b'[' && b[b.len() - 1] == b']') || (b[0] == b'(' && b[b.len() - 1] == b')'))
        {
            let inner = &p[1..p.len() - 1];
            if !inner.is_empty() && !LEVEL_WORDS.contains(&inner.to_uppercase().as_str()) {
                return truncate(inner, 100);
            }
        }
    }
    String::new()
}

fn truncate(s: &str, max: usize) -> String {
    if s.len() > max {
        // 不能从多字节字符（中文/emoji 等 UTF-8）中间切，否则 panic；
        // 向后退到最近的字符边界，尽量贴近字节预算又不拆字符
        let mut end = max;
        while end > 0 && !s.is_char_boundary(end) {
            end -= 1;
        }
        s[..end].to_string()
    } else {
        s.to_string()
    }
}

/// 解析行首时间戳 yyyy-MM-dd HH:mm:ss[.SSS]（兼容逗号毫秒），返回 (年,月,日,时,分,秒,毫秒, 匹配结束位置)
fn parse_ts_fields(line: &str) -> Option<(i32, u32, u32, u32, u32, u32, u32, usize)> {
    let b = line.as_bytes();
    if b.len() < 19 {
        return None;
    }
    let y = digits(b, 0, 4)? as i32;
    if b[4] != b'-' {
        return None;
    }
    let mo = digits(b, 5, 2)?;
    if b[7] != b'-' {
        return None;
    }
    let d = digits(b, 8, 2)?;
    if b[10] != b' ' && b[10] != b'T' {
        return None;
    }
    let h = digits(b, 11, 2)?;
    if b[13] != b':' {
        return None;
    }
    let mi = digits(b, 14, 2)?;
    if b[16] != b':' {
        return None;
    }
    let s = digits(b, 17, 2)?;
    let mut ms = 0u32;
    let mut end = 19usize;
    if b.len() > 19 && (b[19] == b'.' || b[19] == b',') {
        let mut k = 0usize;
        let mut v = 0u32;
        while k < 3 && 20 + k < b.len() && b[20 + k].is_ascii_digit() {
            v = v * 10 + (b[20 + k] - b'0') as u32;
            k += 1;
        }
        if k == 0 {
            return None;
        }
        while k < 3 {
            v *= 10;
            k += 1;
        }
        ms = v;
        end = 20 + k;
    }
    Some((y, mo, d, h, mi, s, ms, end))
}

fn digits(b: &[u8], i: usize, n: usize) -> Option<u32> {
    if i + n > b.len() {
        return None;
    }
    let mut v = 0u32;
    for k in 0..n {
        if !b[i + k].is_ascii_digit() {
            return None;
        }
        v = v * 10 + (b[i + k] - b'0') as u32;
    }
    Some(v)
}

/// 检测各类时间戳，返回匹配结束位置（用于跳过时间戳做 logger/线程推断）。
/// 顺序：Java/ISO8601（行首）→ nginx error（行首）→ nginx access（行内）→ syslog（行首）。
pub fn ts_end(first: &str) -> usize {
    if let Some((_, _, _, _, _, _, _, e)) = parse_ts_fields(first) {
        return e;
    }
    if let Some((_, _, _, _, _, _, _, e)) = nginx_err_ts(first) {
        return e;
    }
    if let Some((_, _, _, _, _, _, _, e)) = nginx_acc_ts(first) {
        return e;
    }
    if let Some((_, _, _, _, _, _, _, e)) = syslog_ts(first) {
        return e;
    }
    0
}

/// 行内是否含有可识别的时间戳（nginx access 允许时间戳在行内，其余要求从行首开始）
pub fn has_timestamp(line: &str) -> bool {
    if nginx_acc_ts(line).is_some() {
        return true;
    }
    if parse_ts_fields(line).is_some() {
        return true;
    }
    if nginx_err_ts(line).is_some() {
        return true;
    }
    if syslog_ts(line).is_some() {
        return true;
    }
    false
}

/// 把分解出的年月日时分秒毫秒转成毫秒时间戳（按本机时区解释，与服务端语义一致）
fn to_epoch(y: i32, mo: u32, d: u32, h: u32, mi: u32, s: u32, ms: u32) -> Option<i64> {
    let date = chrono::NaiveDate::from_ymd_opt(y, mo, d)?;
    let dt = date.and_hms_milli_opt(h, mi, s, ms)?;
    let local = Local
        .from_local_datetime(&dt)
        .single()
        .or_else(|| Local.from_local_datetime(&dt).earliest())?;
    Some(local.timestamp_millis())
}

/// nginx error 时间戳：2026/10/10 09:15:32（行首）
fn nginx_err_ts(line: &str) -> Option<(i32, u32, u32, u32, u32, u32, u32, usize)> {
    let b = line.as_bytes();
    if b.len() < 19 {
        return None;
    }
    let y = digits(b, 0, 4)? as i32;
    if b[4] != b'/' {
        return None;
    }
    let mo = digits(b, 5, 2)?;
    if b[7] != b'/' {
        return None;
    }
    let d = digits(b, 8, 2)?;
    if b[10] != b' ' {
        return None;
    }
    let h = digits(b, 11, 2)?;
    if b[13] != b':' {
        return None;
    }
    let mi = digits(b, 14, 2)?;
    if b[16] != b':' {
        return None;
    }
    let s = digits(b, 17, 2)?;
    Some((y, mo, d, h, mi, s, 0, 19))
}

/// nginx access 时间戳：[10/Oct/2026:09:15:32 +0000]（时间戳在行内，允许任意位置）
fn nginx_acc_ts(line: &str) -> Option<(i32, u32, u32, u32, u32, u32, u32, usize)> {
    let b = line.as_bytes();
    let mut i = 0usize;
    while i < b.len() {
        if b[i] == b'[' {
            let s = i + 1;
            if s + 2 > b.len() || !b[s].is_ascii_digit() || !b[s + 1].is_ascii_digit() {
                i += 1;
                continue;
            }
            let d = digits(b, s, 2)?;
            if s + 2 >= b.len() || b[s + 2] != b'/' {
                i += 1;
                continue;
            }
            let ms = s + 3;
            if ms + 3 > b.len() {
                i += 1;
                continue;
            }
            let mo = month_num(&b[ms..ms + 3])?;
            if b[ms + 3] != b'/' {
                i += 1;
                continue;
            }
            let ys = ms + 4;
            if ys + 4 > b.len() {
                i += 1;
                continue;
            }
            let y = digits(b, ys, 4)? as i32;
            if b[ys + 4] != b':' {
                i += 1;
                continue;
            }
            let hs = ys + 5;
            let h = digits(b, hs, 2)?;
            if hs + 2 >= b.len() || b[hs + 2] != b':' {
                i += 1;
                continue;
            }
            let mis = hs + 3;
            let mi = digits(b, mis, 2)?;
            if mis + 2 >= b.len() || b[mis + 2] != b':' {
                i += 1;
                continue;
            }
            let ss = mis + 3;
            let s = digits(b, ss, 2)?;
            return Some((y, mo, d, h, mi, s, 0, ss + 2));
        }
        i += 1;
    }
    None
}

/// syslog 时间戳：Oct 10 09:15:32（行首，无年份，用当前年）
fn syslog_ts(line: &str) -> Option<(i32, u32, u32, u32, u32, u32, u32, usize)> {
    let b = line.as_bytes();
    if b.len() < 3 || !b[0].is_ascii_alphabetic() {
        return None;
    }
    let mo = month_num(&b[0..3])?;
    let mut i = 3;
    while i < b.len() && (b[i] == b' ' || b[i] == b'\t') {
        i += 1;
    }
    if i >= b.len() || !b[i].is_ascii_digit() {
        return None;
    }
    let mut d = 0u32;
    while i < b.len() && b[i].is_ascii_digit() {
        d = d * 10 + (b[i] - b'0') as u32;
        i += 1;
    }
    while i < b.len() && (b[i] == b' ' || b[i] == b'\t') {
        i += 1;
    }
    let h = digits(b, i, 2)?;
    if i + 2 >= b.len() || b[i + 2] != b':' {
        return None;
    }
    let mi = digits(b, i + 3, 2)?;
    if i + 5 >= b.len() || b[i + 5] != b':' {
        return None;
    }
    let s = digits(b, i + 6, 2)?;
    let y = Local::now().year();
    Some((y, mo, d, h, mi, s, 0, i + 8))
}

/// 3 字母月份缩写 → 数字
fn month_num(b: &[u8]) -> Option<u32> {
    let s = std::str::from_utf8(b).ok()?;
    let m = match s {
        "Jan" => 1,
        "Feb" => 2,
        "Mar" => 3,
        "Apr" => 4,
        "May" => 5,
        "Jun" => 6,
        "Jul" => 7,
        "Aug" => 8,
        "Sep" => 9,
        "Oct" => 10,
        "Nov" => 11,
        "Dec" => 12,
        _ => return None,
    };
    Some(m)
}

/// 行首时间戳 -> 毫秒时间戳（支持 Java / nginx error / nginx access / syslog 多种格式）
fn parse_log_time_ms(first: &str) -> Option<i64> {
    if let Some((y, mo, d, h, mi, s, ms, _)) = parse_ts_fields(first) {
        return to_epoch(y, mo, d, h, mi, s, ms);
    }
    if let Some((y, mo, d, h, mi, s, ms, _)) = nginx_err_ts(first) {
        return to_epoch(y, mo, d, h, mi, s, ms);
    }
    if let Some((y, mo, d, h, mi, s, ms, _)) = nginx_acc_ts(first) {
        return to_epoch(y, mo, d, h, mi, s, ms);
    }
    if let Some((y, mo, d, h, mi, s, ms, _)) = syslog_ts(first) {
        return to_epoch(y, mo, d, h, mi, s, ms);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nginx_error_is_new_entry_and_time() {
        let line = "2026/10/10 09:15:32 [error] 1234#0: *5 open() failed";
        assert!(is_new_entry(line), "nginx error 应识别为新条目");
        let e = Entry::from_text(line);
        assert_eq!(e.level, "ERROR");
        assert!(e.ts_ms.is_some(), "nginx error 应能解析时间");
    }

    #[test]
    fn nginx_access_is_new_entry_and_time() {
        let line = "192.168.1.1 - - [10/Oct/2026:09:15:32 +0000] \"GET /index.html HTTP/1.1\" 200 2326 \"-\" \"Mozilla\"";
        assert!(is_new_entry(line), "nginx access 应识别为新条目（时间戳在行内）");
        assert!(Entry::from_text(line).ts_ms.is_some());
    }

    #[test]
    fn nginx_error_lines_split_not_merged() {
        let logs = "2026/10/10 09:15:32 [error] 1: a\n2026/10/10 09:15:33 [warn] 2: b\n2026/10/10 09:15:34 [error] 3: c";
        let mut merger = Merger::new();
        let mut count = 0;
        for l in logs.lines() {
            if merger.push(l).is_some() {
                count += 1;
            }
        }
        if merger.flush().is_some() {
            count += 1;
        }
        assert_eq!(count, 3, "nginx error 三行应拆成 3 条，而不是合并成 1 条");
    }

    #[test]
    fn java_stacktrace_still_merged() {
        let logs = "2026-10-10 09:15:32.123 INFO  c.F - start\njava.lang.NullPointerException\n\tat c.F.run(F.java:10)\n2026-10-10 09:15:33.456 ERROR c.F - end";
        let mut merger = Merger::new();
        let mut count = 0;
        for l in logs.lines() {
            if merger.push(l).is_some() {
                count += 1;
            }
        }
        if merger.flush().is_some() {
            count += 1;
        }
        assert_eq!(count, 2, "Java 含堆栈应合并为 2 条，不能有回归");
    }

    #[test]
    fn syslog_detected() {
        let line = "Oct 10 09:15:32 myserver sshd[123]: Failed password";
        assert!(is_new_entry(line));
        assert!(Entry::from_text(line).ts_ms.is_some());
    }

    #[test]
    fn iso8601_with_tz() {
        let line = "2026-10-10T09:15:34.789+08:00 DEBUG com.demo.Zoo - iso";
        assert!(is_new_entry(line));
        assert!(Entry::from_text(line).ts_ms.is_some());
    }
}
