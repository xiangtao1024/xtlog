/**
 * 日志行解析：与 xtlog-server 的 LogTextIngestor 保持一致的行为，
 * 这样「服务端自己采集文件」和「客户端采集后推送」两条路得到的结果一致。
 *
 * 负责三件事：
 *  1. 多行合并：把 Java 异常堆栈这类续行并进上一条日志（遇到「新条目」特征才切分）；
 *  2. 从行首解析时间戳，作为日志发生时间（服务端据此决定落在哪个月份表）；
 *  3. 推断级别 / logger / 线程名。
 */

use chrono::{Local, TimeZone};

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

/// 是否为一条新日志的起始行：行首有时间戳，或首词就是级别词
pub fn is_new_entry(line: &str) -> bool {
    if line.is_empty() {
        return false;
    }
    if parse_ts_fields(line).is_some() {
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
    let rest = match parse_ts_fields(first) {
        Some((_y, _mo, _d, _h, _mi, _s, _ms, end)) => first[end..].trim(),
        None => first,
    };
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
    let rest = match parse_ts_fields(first) {
        Some((_y, _mo, _d, _h, _mi, _s, _ms, end)) => first[end..].trim(),
        None => first,
    };
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
        s[..max].to_string()
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

/// 行首时间戳 -> 毫秒时间戳（按本机时区解释，与服务端 SimpleDateFormat 的语义一致）
fn parse_log_time_ms(first: &str) -> Option<i64> {
    let (y, mo, d, h, mi, s, ms, _end) = parse_ts_fields(first)?;
    let date = chrono::NaiveDate::from_ymd_opt(y, mo, d)?;
    let dt = date.and_hms_milli_opt(h, mi, s, ms)?;
    let local = Local
        .from_local_datetime(&dt)
        .single()
        .or_else(|| Local.from_local_datetime(&dt).earliest())?;
    Some(local.timestamp_millis())
}
