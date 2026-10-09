use std::collections::HashMap;
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use crate::config::Collector;
use crate::parse::{Entry, Merger};
use crate::Item;

/// 单次 poll 最多读取的字节数：避免首次启动读一个超大历史文件把内存打满
const MAX_READ_PER_POLL: u64 = 1024 * 1024;
const POLL_INTERVAL: Duration = Duration::from_millis(500);
/// 命令采集进程退出后的重启退避
const CMD_RESTART_GAP: Duration = Duration::from_millis(3000);

// ============================================================================
//  文件采集的读取偏移持久化
// ============================================================================

pub struct OffsetStore {
    map: Mutex<HashMap<String, u64>>,
    path: Option<PathBuf>,
    dirty: AtomicBool,
}

impl OffsetStore {
    pub fn new(path: Option<String>) -> Arc<OffsetStore> {
        let p = path.filter(|s| !s.trim().is_empty()).map(PathBuf::from);
        let map = match &p {
            Some(pp) => std::fs::read_to_string(pp)
                .ok()
                .and_then(|t| serde_json::from_str::<HashMap<String, u64>>(&t).ok())
                .unwrap_or_default(),
            None => HashMap::new(),
        };
        Arc::new(OffsetStore {
            map: Mutex::new(map),
            path: p,
            dirty: AtomicBool::new(false),
        })
    }

    pub fn get(&self, key: &str) -> Option<u64> {
        self.map.lock().ok().and_then(|m| m.get(key).copied())
    }

    pub fn set(&self, key: &str, val: u64) {
        if let Ok(mut m) = self.map.lock() {
            if m.get(key).copied() != Some(val) {
                m.insert(key.to_string(), val);
                self.dirty.store(true, Ordering::Relaxed);
            }
        }
    }

    /// 落盘（被后台线程周期性调用）。没配 stateFile 时是空操作。
    pub fn flush(&self) {
        if self.path.is_none() || !self.dirty.swap(false, Ordering::Relaxed) {
            return;
        }
        let snapshot = match self.map.lock() {
            Ok(m) => m.clone(),
            Err(_) => return,
        };
        if let (Some(p), Ok(text)) = (&self.path, serde_json::to_string(&snapshot)) {
            // 先写临时文件再改名，避免进程被杀时写坏状态文件
            let tmp = p.with_extension("state.tmp");
            if std::fs::write(&tmp, text).is_ok() {
                let _ = std::fs::rename(&tmp, p);
            }
        }
    }
}

// ============================================================================
//  文件跟随（tail -F 语义）
// ============================================================================

struct FileFollower {
    path_str: String,
    path: PathBuf,
    gbk: bool,
    from_start: bool,
    offset: u64,
    inited: bool,
    /// 跨 poll 残留的半行字节（按字节保存，避免多字节字符被切断）
    raw: Vec<u8>,
    offsets: Arc<OffsetStore>,
}

impl FileFollower {
    fn new(path: &str, encoding: Option<&str>, from_start: bool, offsets: Arc<OffsetStore>) -> Self {
        let gbk = encoding
            .map(|e| e.eq_ignore_ascii_case("gbk") || e.eq_ignore_ascii_case("gb2312"))
            .unwrap_or(false);
        let offset = offsets.get(path).unwrap_or(0);
        FileFollower {
            path_str: path.to_string(),
            path: PathBuf::from(path),
            gbk,
            from_start,
            offset,
            inited: false,
            raw: Vec::new(),
            offsets,
        }
    }

    /// 读出本次新增的完整行（最后半行留在缓冲里等下次）
    fn poll(&mut self) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        let mut f = match File::open(&self.path) {
            Ok(f) => f,
            Err(_) => return out, // 文件不存在/无权限：下个周期再试
        };
        let size = match f.metadata() {
            Ok(m) => m.len(),
            Err(_) => return out,
        };

        if !self.inited {
            self.inited = true;
            if self.offset == 0 && !self.from_start {
                // 默认只推启动之后新增的日志，避免把历史文件整份重放一遍
                self.offset = size;
            }
        }
        if size < self.offset {
            // 文件被截断或日志轮转（如 app.log -> app.log.1）后重新生成
            self.offset = 0;
            self.raw.clear();
        }
        if size > self.offset {
            let want = std::cmp::min(size - self.offset, MAX_READ_PER_POLL) as usize;
            if f.seek(SeekFrom::Start(self.offset)).is_ok() {
                let mut buf = vec![0u8; want];
                let mut got = 0usize;
                while got < want {
                    match f.read(&mut buf[got..]) {
                        Ok(0) => break,
                        Ok(n) => got += n,
                        Err(_) => break,
                    }
                }
                if got > 0 {
                    self.raw.extend_from_slice(&buf[..got]);
                    self.offset += got as u64;
                    // 只在「整行」边界上切分：以最后一个换行为界，剩余部分留在 raw
                    if let Some(pos) = self.raw.iter().rposition(|&b| b == b'\n') {
                        let mut complete: Vec<u8> = self.raw.drain(..=pos).collect();
                        // 去掉末尾那个换行再 split，否则会多出一个空串
                        if complete.last() == Some(&b'\n') {
                            complete.pop();
                        }
                        let text = decode(&complete, self.gbk);
                        for l in text.split('\n') {
                            out.push(l.to_string());
                        }
                    }
                }
            }
        }
        if !out.is_empty() {
            self.offsets.set(&self.path_str, self.offset);
        }
        out
    }
}

fn decode(bytes: &[u8], gbk: bool) -> String {
    if gbk {
        let (cow, _enc, _err) = encoding_rs::GBK.decode(bytes);
        cow.into_owned()
    } else {
        String::from_utf8_lossy(bytes).into_owned()
    }
}

// ============================================================================
//  进程登记表：收到退出信号时用来杀掉拉起的命令进程
// ============================================================================

pub struct ProcRegistry {
    pids: Mutex<HashMap<usize, u32>>,
}

impl ProcRegistry {
    pub fn new() -> Arc<ProcRegistry> {
        Arc::new(ProcRegistry {
            pids: Mutex::new(HashMap::new()),
        })
    }
    pub fn put(&self, idx: usize, pid: u32) {
        if let Ok(mut m) = self.pids.lock() {
            m.insert(idx, pid);
        }
    }
    pub fn remove(&self, idx: usize) {
        if let Ok(mut m) = self.pids.lock() {
            m.remove(&idx);
        }
    }
    /// 杀掉所有登记的进程，并尽量连子进程树一起杀（避免变孤儿继续跑）
    pub fn kill_all(&self) {
        let map = match self.pids.lock() {
            Ok(m) => m.clone(),
            Err(_) => return,
        };
        for (_idx, pid) in map {
            kill_tree(pid);
        }
    }
}

/// 按 pid 杀进程树：先杀子进程再杀主进程，顺序反了子进程会被过继、枚举不到
fn kill_tree(pid: u32) {
    let s = pid.to_string();
    if std::env::consts::OS == "windows" {
        let _ = Command::new("taskkill")
            .args(["/T", "/F", "/PID", &s])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    } else {
        // 先给孩子发 TERM，再杀主进程
        let _ = Command::new("pkill")
            .args(["-TERM", "-P", &s])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
        let _ = Command::new("kill")
            .args(["-TERM", &s])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
}

// ============================================================================
//  采集器主循环
// ============================================================================

pub fn run_file_collector(
    cfg: &Collector,
    idx: usize,
    tx: Sender<Item>,
    stop: Arc<AtomicBool>,
    offsets: Arc<OffsetStore>,
) {
    let enc = cfg.encoding.as_deref();
    let mut followers: Vec<(FileFollower, Merger)> = cfg
        .paths
        .iter()
        .map(|p| {
            (
                FileFollower::new(p, enc, cfg.from_start, offsets.clone()),
                Merger::new(),
            )
        })
        .collect();

    while !stop.load(Ordering::Relaxed) {
        let mut got_any = false;
        for (f, merger) in followers.iter_mut() {
            for line in f.poll() {
                got_any = true;
                if let Some(done) = merger.push(&line) {
                    emit(idx, &done, &tx);
                }
            }
        }
        if stop.load(Ordering::Relaxed) {
            break;
        }
        if got_any {
            // 有数据就快速再读一轮（可能还有后续的续行没落盘）
            thread::sleep(Duration::from_millis(50));
        } else {
            // 文件空闲：把攒着的最后一条发出去。
            // 否则「最后一条日志」会一直等到下一条出现才推送，文件一停写就永远发不出去。
            for (_f, merger) in followers.iter_mut() {
                if let Some(done) = merger.flush() {
                    emit(idx, &done, &tx);
                }
            }
            thread::sleep(POLL_INTERVAL);
        }
    }
    // 退出前刷掉残留缓冲，避免最后一条多行日志丢尾
    for (_f, merger) in followers.iter_mut() {
        if let Some(done) = merger.flush() {
            emit(idx, &done, &tx);
        }
    }
}

pub fn run_cmd_collector(
    cfg: &Collector,
    idx: usize,
    tx: Sender<Item>,
    stop: Arc<AtomicBool>,
    registry: Arc<ProcRegistry>,
) {
    let command = cfg.command.clone().unwrap_or_default();
    let shell = cfg.shell.clone();
    let mut merger = Merger::new();

    while !stop.load(Ordering::Relaxed) {
        let spawned = spawn(&shell, &command);
        let mut child = match spawned {
            Ok(c) => c,
            Err(e) => {
                eprintln!("[xtlog-client] 采集器#{} 启动命令失败：{}", idx, e);
                thread::sleep(CMD_RESTART_GAP);
                continue;
            }
        };

        let stdout = child.stdout.take();
        let stderr = child.stderr.take();
        let pid = child.id();
        registry.put(idx, pid);

        // stderr 合并进日志流，命令自身的报错也能在界面上看到
        let tx2 = tx.clone();
        let h_err = stderr.map(|se| {
            thread::spawn(move || {
                let reader = BufReader::new(se);
                for line in reader.lines() {
                    match line {
                        Ok(l) => {
                            let e = Entry::from_text(&l);
                            let _ = tx2.send(Item { idx, entry: e });
                        }
                        Err(_) => break,
                    }
                }
            })
        });

        if let Some(so) = stdout {
            let reader = BufReader::new(so);
            for line in reader.lines() {
                match line {
                    Ok(l) => {
                        if let Some(done) = merger.push(&l) {
                            emit(idx, &done, &tx);
                        }
                    }
                    Err(_) => break,
                }
            }
        }
        if let Some(h) = h_err {
            let _ = h.join();
        }
        if stop.load(Ordering::Relaxed) {
            // 收到退出信号：主动杀掉，别让它变成孤儿
            kill_tree(pid);
        }
        let _ = child.wait();
        registry.remove(idx);
        if let Some(done) = merger.flush() {
            emit(idx, &done, &tx);
        }

        if stop.load(Ordering::Relaxed) {
            break;
        }
        // 常驻命令（tail -F / journalctl -f / docker logs -f）退出后自动重连
        thread::sleep(CMD_RESTART_GAP);
    }
}

fn emit(idx: usize, text: &str, tx: &Sender<Item>) {
    let entry = Entry::from_text(text);
    let _ = tx.send(Item { idx, entry });
}

/// 按系统选择解释器：Linux /bin/sh -c，Windows cmd.exe /c
fn spawn(shell: &Option<String>, command: &str) -> std::io::Result<Child> {
    let mut cmd = match shell {
        Some(s) if !s.trim().is_empty() => {
            let mut c = Command::new(s);
            c.arg("-c").arg(command);
            c
        }
        _ => {
            if std::env::consts::OS == "windows" {
                let mut c = Command::new("cmd.exe");
                c.arg("/c").arg(command);
                c
            } else {
                let mut c = Command::new("/bin/sh");
                c.arg("-c").arg(command);
                c
            }
        }
    };
    cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
    cmd.spawn()
}
