use serde::Deserialize;

/// 顶层配置：一个 xtlog 服务地址 + N 个采集器
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    /// xtlog 服务地址，如 http://192.168.1.10:18080（结尾不要带 /）
    pub server: String,

    /// 来源 IP。留空则自动探测本机 IP（取「去往服务端」的那个出口 IP）。
    /// 多网卡判断不准时，在这里写死，例如 "10.20.30.41"
    #[serde(default)]
    pub src_ip: Option<String>,

    /// 单批最多多少条就立刻推送（默认 100）
    #[serde(default = "default_batch_size")]
    pub batch_size: usize,

    /// 距上次推送超过多少毫秒就推一次（默认 1000）
    #[serde(default = "default_flush_interval_ms")]
    pub flush_interval_ms: u64,

    /// 推送失败时的重试次数（默认 3，退避 1s/2s/4s）
    #[serde(default = "default_retry")]
    pub retry: usize,

    /// 内存队列上限，超出后丢弃新日志并告警，防止服务端长时间不可用时内存爆掉（默认 20000）
    #[serde(default = "default_queue_max")]
    pub queue_max: usize,

    /// 文件采集的读取偏移持久化文件。配置后重启可从上次位置继续，不重复也不丢日志。
    #[serde(default)]
    pub state_file: Option<String>,

    /// 采集器数组：每个元素对应 xtlog 里的一个应用
    pub collectors: Vec<Collector>,
}

/// 单个采集器 = xtlog 里的一个应用
#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Collector {
    /// 应用接入 Token（xtlog 应用管理页生成）
    pub token: String,

    /// 应用 ID，可选。不填时服务端按 token 推导
    #[serde(default)]
    pub app_id: Option<i64>,

    /// 采集类型：file（文件） / cmd（命令）
    #[serde(rename = "type")]
    pub kind: String,

    /// 备注名，只用于日志输出，便于区分
    #[serde(default)]
    pub name: Option<String>,

    // ---- type=file ----
    /// 要采集的文件路径，支持多个
    #[serde(default)]
    pub paths: Vec<String>,

    /// 文件编码：utf8（默认）或 gbk
    #[serde(default)]
    pub encoding: Option<String>,

    /// true = 从文件开头读（补历史日志）；默认 false = 只推启动之后新增的日志
    #[serde(default)]
    pub from_start: bool,

    // ---- type=cmd ----
    /// 要执行的命令，例如 journalctl -u game -f -o short-iso
    #[serde(default)]
    pub command: Option<String>,

    /// 执行命令的 shell。Linux 默认 /bin/sh -c，Windows 默认 cmd.exe /c
    #[serde(default)]
    pub shell: Option<String>,
}

fn default_batch_size() -> usize {
    100
}
fn default_flush_interval_ms() -> u64 {
    1000
}
fn default_retry() -> usize {
    3
}
fn default_queue_max() -> usize {
    20000
}

impl Config {
    /// 读取并校验配置。错误时返回人类可读的问题描述（含行号上下文无关，但足够定位）
    pub fn load(path: &str) -> Result<Config, String> {
        let text = std::fs::read_to_string(path)
            .map_err(|e| format!("读取配置文件失败 {}: {}", path, e))?;
        let cfg: Config = serde_json::from_str(&text)
            .map_err(|e| format!("配置文件 {} 解析失败：{}", path, e))?;
        cfg.validate()?;
        Ok(cfg)
    }

    fn validate(&self) -> Result<(), String> {
        if self.server.trim().is_empty() {
            return Err("配置缺少 server（xtlog 服务地址）".into());
        }
        let s = self.server.trim().to_lowercase();
        if !s.starts_with("http://") && !s.starts_with("https://") {
            return Err(format!(
                "server 必须以 http:// 或 https:// 开头，当前为 {}",
                self.server
            ));
        }
        if self.server.trim_end_matches('/').is_empty() {
            return Err("server 地址无效".into());
        }
        if self.collectors.is_empty() {
            return Err("collectors 为空：至少要配置一个采集器".into());
        }
        for (i, c) in self.collectors.iter().enumerate() {
            let tag = format!("collectors[{}]{}", i, c.name_tag());
            if c.token.trim().is_empty() {
                return Err(format!("{} 缺少 token", tag));
            }
            match c.kind.to_lowercase().as_str() {
                "file" => {
                    if c.paths.is_empty() {
                        return Err(format!("{} 是 file 类型但 paths 为空", tag));
                    }
                }
                "cmd" => {
                    if c.command.as_deref().unwrap_or("").trim().is_empty() {
                        return Err(format!("{} 是 cmd 类型但 command 为空", tag));
                    }
                }
                other => {
                    return Err(format!(
                        "{} 的 type 无效：{}（只支持 file / cmd）",
                        tag, other
                    ));
                }
            }
        }
        Ok(())
    }
}

impl Collector {
    pub fn name_tag(&self) -> String {
        match &self.name {
            Some(n) if !n.trim().is_empty() => format!("({})", n),
            _ => String::new(),
        }
    }
    pub fn is_file(&self) -> bool {
        self.kind.eq_ignore_ascii_case("file")
    }
}
