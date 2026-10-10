# xtlog-client

xtlog 的**客户端采集推送器**：一个独立的可执行文件，放在要采集日志的那台机器上，
按配置文件采集本机日志并 HTTP 推送到 xtlog-server。

适用场景：日志不在 xtlog 所在机器上（多台服务器 / 微服务各实例），
又不想在每台机器上装 JDK 或用 SSH 打通。

```
[被采集机器]  xtlog-client  --config=config.json  ──HTTP POST /api/logs──>  [xtlog-server]
                  │
                  ├─ file：跟随一个或多个日志文件（tail -F 语义）
                  └─ cmd ：常驻执行一条命令，把输出当日志流
```

## 用法

```bash
./xtlog-client --config=config.json      # 推荐
./xtlog-client --config config.json      # 等价
./xtlog-client -c config.json
./xtlog-client                           # 默认读当前目录的 config.json
./xtlog-client --help
./xtlog-client --version
```

启动后会打印配置概要（服务地址、探测到的本机 IP、各采集器），然后持续运行，
`Ctrl+C` 或 `SIGTERM` 优雅退出（停采集 → 杀掉拉起的命令进程 → 把剩余日志推完）。

## 配置说明

```json
{
  "server": "http://192.168.1.10:18080",
  "srcIp": "",
  "batchSize": 100,
  "flushIntervalMs": 1000,
  "retry": 3,
  "queueMax": 20000,
  "stateFile": "./xtlog-client.state",
  "collectors": [ ... ]
}
```

### 顶层字段

| 字段 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `server` | 是 | — | xtlog 服务地址，如 `http://192.168.1.10:18080`。**结尾不要带 `/`**；只支持 `http://`（见下方「关于 https」） |
| `srcIp` | 否 | 自动探测 | 来源 IP。留空时探测本机「去往服务端」的出口 IP；多网卡判断不准就在这里写死，如 `10.20.30.41` |
| `batchSize` | 否 | 100 | 单批最多多少条就立即推送 |
| `flushIntervalMs` | 否 | 1000 | 距上次推送超过多少毫秒就推一次（低流量时保证及时性） |
| `retry` | 否 | 3 | 推送失败重试次数，退避 500ms → 1s → 2s |
| `queueMax` | 否 | 20000 | 内存队列上限。服务端长时间不可用时，超出后丢弃新日志并告警，避免内存被撑爆 |
| `stateFile` | 否 | 不持久化 | 文件采集的**读取偏移**落盘文件。配了之后重启能从上次位置继续，不重复也不丢。详见下方「stateFile 是什么」 |
| `collectors` | 是 | — | 采集器数组，每个元素对应 xtlog 里的**一个应用** |

### stateFile 是什么

**状态文件**：记录每个被采集文件「已经读到第几个字节」，供进程重启后接着读。内容就是一行 JSON：

```json
{"./app.log":102,"/var/log/order/error.log":15380}
```

- **只有 `file` 采集器会用到它**。`cmd` 采集器没有"读到哪"的概念（命令退出就重跑），配了也不会往里写东西。
- 相对路径（如 `./xtlog-client.state`）相对于**启动时的当前工作目录**，不是 exe 所在目录。启动时控制台会打印它的绝对路径，照着找即可：

  ```
  [xtlog-client] 状态文件：C:\Users\xt\AppData\Local\Temp\xtlog-state-test\./xtlog-client.state（记录文件采集偏移，仅 file 采集器使用）
  ```

- **什么时候产生**：配了 `stateFile` 且在跑 `file` 采集器时，启动后**立刻**就会创建（哪怕一条新日志都还没有，也会先记下起始偏移），之后每 3 秒刷一次，退出前再刷一次。
  所以「运行了却没看到文件」通常是这三个原因：配置里没写 / 写成了空串、只有 `cmd` 采集器、或者去 exe 所在目录找了而实际落在工作目录。
- 不配的后果：进程每次重启都从**文件末尾**开始读（`fromStart=false` 时），停机期间写进来的日志会丢。生产环境建议配上。
- 想补历史日志：把 `fromStart` 设为 `true`，或删掉状态文件后重启。

### collectors[] 字段

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `token` | 是 | 应用在 xtlog 里的接入 Token（应用管理页生成） |
| `appId` | 否 | 应用 ID。不填时服务端按 token 推导 |
| `type` | 是 | `file`（文件采集）或 `cmd`（命令采集） |
| `name` | 否 | 备注名，只用于控制台输出，便于区分多个采集器 |

**type = file**

| 字段 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `paths` | 是 | — | 文件路径数组，可多个 |
| `encoding` | 否 | `utf8` | 文件编码，`utf8` 或 `gbk`（中文 Windows 程序常用 GBK） |
| `fromStart` | 否 | `false` | `false`=只推启动后新增的日志；`true`=从文件开头读（补历史） |

**type = cmd**

| 字段 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `command` | 是 | — | 要执行的命令，应持续输出，如 `journalctl -u game -f -o short-iso`、`tail -F /var/log/app.log`、`docker logs -f my-container` |
| `shell` | 否 | Linux `/bin/sh -c`；Windows `cmd.exe /c` | 执行命令的解释器 |

## 采集行为

- **通用日志，不挑格式**：只要是文本日志都能采，**不限于 Spring Boot / Logback**。内置多种时间戳识别，能正确切分条目并提取时间：
  - Java / Logback / ISO8601：`2026-10-10 09:15:32.123` 或 `2026-10-10T09:15:32+08:00`
  - **Nginx error**：`2026/10/10 09:15:32 [error] 1234#0: ...`（每行独立、级别正确、时间正确）
  - **Nginx access**：`192.168.1.1 - - [10/Oct/2026:09:15:32 +0000] "GET /x" 200 ...`
  - **Syslog**：`Oct 10 09:15:32 myserver sshd[123]: ...`
  - 其它带行首时间戳或行首级别词的日志同样可用。
- **多行合并**：Java 异常堆栈这类续行会并进上一条日志（遇到行首时间戳或级别词才切分），与服务端自己采集文件的结果一致。
- **级别/时间/logger 解析**：客户端解析后作为结构化字段上报，界面上级别、日志时间、Logger、线程都能正确显示（而不是全部 INFO）。解析规则与服务端 `LogTextIngestor` 保持一致。
- **日志轮转**：`app.log` 被切割改名后会检测到（文件变小）并从新文件头开始读。
- **命令断线重连**：常驻命令退出后 3 秒自动重新拉起。
- **来源 IP**：自动探测，界面上用来区分「这条日志来自哪台机器」。

## 关于 https

客户端为纯标准库实现、不带 TLS 栈，**只支持 `http://`**。若 xtlog 需要走 https，
请在前面挂 nginx 做 TLS 终结后以 http 转发到 18080，客户端填 `http://127.0.0.1:18080`。
直接填 `https://` 会明确报错而不是静默失败。

## 常驻运行（systemd）

```ini
[Unit]
Description=xtlog client agent
After=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/xtlog-client
ExecStart=/opt/xtlog-client/xtlog-client --config=/opt/xtlog-client/config.json
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now xtlog-client
sudo journalctl -u xtlog-client -f
```

## 构建

**在目标机器上直接构建（推荐，最省事）**

```bash
./build.sh              # 产物：target/release/xtlog-client
./build.sh linux-x64    # 明确构建 Linux x86_64
./build.sh linux-arm64  # Linux ARM64
```

只需要装 Rust 工具链（`curl https://sh.rustup.rs | sh`），构建出来的就是单一静态可执行文件，
目标机无需任何运行时。

**关于交叉编译**

Windows 上**无法**直接产出 Linux 可执行文件：Rust 交叉链接需要目标平台的链接器（ELF linker），
而稳定版 Rust 的 `-C link-self-contained=+linker` 与 `-C linker-flavor=gnu-lld` 都属于不稳定选项
（需要 nightly）。因此要么在 Linux 机器上构建，要么用 `cross`（依赖 Docker）。

已验证：Windows 本机 `cargo build --release` 产出 `xtlog-client.exe`（约 550KB），可直接运行验证。
