# xtlog-server 部署说明

统一日志收集与查看服务。基于自研框架 xtboot，**零外部容器依赖**，一个 jar 跑起来即可。

- 默认端口：`18080`
- 默认管理员：`admin / admin123`（**首次登录后请在「个人中心」修改密码**）
- 默认数据源：安装目录下 `data/xtlog.db`（SQLite，WAL 模式），按月分表 `xt_log_entry_YYYYMM`

---

## 1. 目录结构

```
xtlog-server/
├── xtlog-server.jar       # 可执行包（后端 + 前端静态资源 + 依赖，已含 SQLite 驱动）
├── config/
│   └── application.properties    # 运行配置（端口 / 线程 / GZIP / SSE 等，改完重启生效）
├── data/                  # 数据目录（SQLite 库文件、数据源持久化配置、PID 文件）
├── drivers/               # 可选：MySQL / PostgreSQL 驱动 jar，留空即用 SQLite
├── service/
│   └── xtlog.service      # Linux systemd 服务文件（改 JAVA_HOME 后拷到 /etc/systemd/system）
├── logs/                  # 后台运行时的控制台日志
├── bin/
│   ├── start.bat          # Windows 前台启动（Ctrl+C 结束）
│   ├── start-bg.bat       # Windows 后台启动（PID 写入 data\xtlog.pid）
│   ├── stop.bat           # Windows 停止
│   ├── start.sh           # Linux / macOS 前台启动
│   └── stop.sh            # Linux / macOS 停止
└── README.md
```

## 2. 环境要求

- **JDK 8 及以上**（推荐 JDK 17）。确保 `java -version` 可用，或设置 `JAVA_HOME`。
- 内存：默认堆 `-Xms256m -Xmx512m`，可通过环境变量 `JAVA_OPTS` 覆盖，例如：

```bat
set JAVA_OPTS=-Xmx2g
bin\start-bg.bat
```

## 3. 启动

### Windows

```bat
rem 前台运行（看日志方便）
bin\start.bat

rem 后台运行
bin\start-bg.bat

rem 停止
bin\stop.bat
```

### Linux（推荐：systemd 托管）

```bash
# 1. 建专用运行用户（可选但推荐）
sudo useradd -r -s /sbin/nologin xtlog
sudo chown -R xtlog:xtlog /opt/xtlog-server

# 2. 改服务文件里的 JDK 路径
sudo vi /opt/xtlog-server/service/xtlog.service   # Environment="JAVA_HOME=..."

# 3. 安装并启动
sudo cp /opt/xtlog-server/service/xtlog.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now xtlog

# 4. 常用运维
sudo systemctl status xtlog
sudo journalctl -u xtlog -f      # 跟随日志
sudo systemctl restart xtlog
sudo systemctl stop xtlog        # 优雅停机（最多等 20s）
```

服务文件已配好 `Restart=on-failure`（异常退出 5 秒后自动拉起）与 `ExecStop` 优雅停机，
不用再自己写 nohup / pid 文件。日志默认进 journald。

### Linux / macOS（手工运行，不用 systemd）

```bash
chmod +x bin/*.sh

# 前台运行
bin/start.sh

# 后台运行
nohup bin/start.sh > /dev/null 2>&1 &
echo $! > data/xtlog.pid

# 停止
bin/stop.sh
```

启动成功后控制台会出现类似：

```
... started on port: 18080
```

浏览器访问 **http://<服务器IP>:18080** ，用 `admin / admin123` 登录。

## 4. 常用配置

### 改端口

编辑 `config/application.properties`：

```properties
server.port=8080
```

改完重启。（不要试图用 `-Dserver.port=xxx` 覆盖：框架的配置文件优先级高于系统属性。）

### 改数据目录

默认就是安装目录下的 `data`。如需放到别处，启动前设置环境变量：

```bat
set XTLOG_DATA_DIR=D:\xtlog-data
bin\start-bg.bat
```

```bash
export XTLOG_DATA_DIR=/var/lib/xtlog
bin/start.sh
```

### 切换到 MySQL / PostgreSQL

1. 把驱动 jar 放进 `drivers/`（`mysql-connector-j-8.x.jar` 或 `postgresql-42.x.jar`）；
2. 重启服务；
3. 登录后在 **设置 → 初始化配置** 里填写 JDBC URL 并保存，切换立即生效；
4. 配置会写入 `data/datasource.properties`，后续每次启动自动使用；删除该文件并重启即回到 SQLite。

注意：SQLite 模式下先后月初次写入会自动建 `xt_log_entry_YYYYMM` 表；切到 MySQL / PG 前请先确认目标库已建好对应的月表，或在界面上触发一次初始化。

## 5. 接入日志上报

在管理后台「应用管理」里新建应用，系统会自动生成 **token**。应用侧：

```bash
curl -X POST http://<host>:18080/api/logs \
  -H "Content-Type: application/json" \
  -d '{"token":"<应用token>","level":"INFO","logger":"com.demo.Order","message":"订单创建成功"}'
```

批量上报（一次多条）：

```bash
curl -X POST http://<host>:18080/api/logs \
  -H "Content-Type: application/json" \
  -d '{"token":"<应用token>","logs":[{"level":"INFO","message":"a"},{"level":"ERROR","message":"b"}]}'
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `token` | 应用密钥，必填 |
| `logs[]` | 批量日志数组；一次一条时用 `level` / `message` 等平铺字段 |
| `level` | TRACE / DEBUG / INFO / WARN / ERROR，缺省 INFO |
| `logger` / `thread` / `traceId` | 可选，用于定位来源 |
| `srcIp` | 可选但**建议传**：上报端所在机器的 IP。微服务多实例部署时，用它区分这条日志来自哪台机器 |
| `timestamp` | 可选，毫秒时间戳。**强烈建议传**，它决定日志落在哪个月份表；不传则按服务端接收时间 |

带来源 IP 上报示例（微服务多实例场景）：

```bash
curl -X POST http://<host>:18080/api/logs \
  -H "Content-Type: application/json" \
  -d '{"token":"<应用token>","level":"ERROR","logger":"com.svc.Gateway","message":"网关超时","srcIp":"10.0.0.21"}'
```

### 5.1 日志的「来源 IP」是怎么来的

每条日志都带一个 `src_ip`，用于在多机/微服务场景下区分**这条日志来自哪台机器**：

| 应用采集方式 | `src_ip` 取值 | 说明 |
| --- | --- | --- |
| HTTP | 上报端通过 `srcIp` 字段**自己带过来** | 微服务各实例上报本机 IP，服务端原样记录 |
| FILE（文件采集） | xtlog 服务**所在机器的 IP** | 日志物理上就落在这台机器上 |
| CMD（命令采集） | xtlog 服务**所在机器的 IP** | 命令在服务所在机器执行，日志即本机日志 |

界面上：日志控制台每条的头部右侧、以及「表格记录」页的 **来源 IP** 列都会显示；双击日志查看详情也能看到。
HTTP 上报端若不传 `srcIp`，该字段为空（界面不显示 IP），服务端不会拿 TCP 连接地址冒充当机器 IP——
因为经过网关/负载均衡时那会是代理的地址，反而误导定位。

#### FILE / CMD 的「本机 IP」是怎么选的

机器上通常有一堆虚拟网卡（WSL2 / Hyper-V 的 `vEthernet (WSL)` → `172.27.0.1`、
Docker 网桥 `docker0` → `172.17.0.1`、VMware / VirtualBox / VPN / Tailscale 等），
只取「第一个网卡」很容易拿到这些地址。xtlog 的做法是给每个 IPv4 候选**打分后选最优**：

| 维度 | 规则 |
| --- | --- |
| 网段 | `192.168.*`（+100）> `10.*`（+90）> `172.16~31.*`（+50，Docker/WSL 常用段，压低）> 其他（+40）；`169.254.*`、`127.*` 丢弃 |
| 网卡 | 有 MAC 地址 +20；名称命中 docker/veth/wsl/vEthernet/virtual/vmware/tap/tun/vpn 等关键字 −60；`isVirtual()` −40；点对点(VPN) −30 |
| 出网路由 | 若该地址正是默认出网路由选中的源地址（UDP connect 探测，不发数据包）+50 |

启动时若存在多个候选，控制台会打印选中结果与各候选得分，便于核对：

```
[xtlog] host ip: 192.168.31.49 (wlan2), candidates [192.168.31.49 (wlan2) score=110, 192.168.56.1 (eth4) score=0]
```

**自动判断不准时可手动指定**（多网卡 / 容器 / 特殊网络环境推荐），两种方式二选一：

```bash
# 方式一：环境变量（systemd 里在 [Service] 加一行）
Environment="XTLOG_HOST_IP=192.168.1.10"

# 方式二：JVM 系统属性（写进 ExecStart）
-Dxtlog.host.ip=192.168.1.10
```

手动指定的值**永远最优先**，且不会再做任何探测。

实时订阅（SSE）：

```bash
curl -N "http://<host>:18080/api/logs/stream?appId=1&satoken=<登录token>"
```

## 5.1 文件 / 命令采集（服务端主动拉）

除了应用主动 HTTP 上报，xtlog 还支持两种**服务端主动采集**的来源类型（在「应用管理」新建/编辑时选择）：

**FILE — 文件采集**：填写日志文件（或目录）路径，服务端每 2 秒按字节偏移增量读取，自动处理轮转与多行堆栈合并。适合应用写本地日志文件的场景。

**CMD — 命令采集**：直接填写一条 shell 命令，服务端执行它并实时读取标准输出作为日志。适合采集 **journald** 这类非文件来源，例如：

```
journalctl -u game.service -f -o short-iso
```

CMD 说明与注意点：

- Linux 用 `/bin/sh -c` 执行（Windows 用 `cmd /c`），支持管道、重定向等 shell 语法。
- **建议用带时间戳的输出格式**（如 `journalctl -o short-iso` 或默认格式）：xtlog 靠「行首时间戳 / 级别词」判断一条日志的开始，从而正确合并多行堆栈；不带时间戳的 `-o cat` 会导致所有行被拼成一条（超过 256KB 时强制切段兜底）。
- journald 的级别映射：`-o short-iso` 不含级别词，默认判为 INFO；若需要 ERROR/WARN 级别，可让命令输出包含级别词，或后续配合 `-o json` 解析 PRIORITY。
- 命令进程崩溃后 xtlog 会在退避后自动重启它；应用被删除或命令被修改时，xtlog 会**杀掉整棵命令进程树**（Windows `taskkill /T`、Linux `pkill -P`）再重启。
- 命令在 xtlog 服务所在机器上以运行 xtlog 的用户身份执行，请勿填入不可信命令。

## 5.2 远程机器的日志怎么采（xtlog-client）

上面 5.1 的文件/命令采集都发生在 **xtlog 服务所在机器**。如果日志在别的服务器上，有三种方式：

| 方式 | 做法 | 适用 |
| --- | --- | --- |
| **xtlog-client（推荐）** | 在被采集机器放一个可执行文件，按配置采集本机文件/命令后 HTTP 推给 xtlog | 机器多、不想打通 SSH、需要正确的来源 IP |
| 命令里写 ssh | CMD 采集填 `ssh -o BatchMode=yes user@host "tail -F /var/log/app.log"`，需配 SSH 免密 | 临时采一两台；**来源 IP 会记成 xtlog 本机 IP** |
| 目录挂载 | NFS / SMB / sshfs 挂到 xtlog 机器后按 5.1 采集 | 已有共享存储 |

**xtlog-client** 是一个独立子项目（源码在仓库 `client/`，Rust 编写，零运行时依赖）：

```bash
./xtlog-client --config=config.json
```

配置示例（完整字段说明见 `client/README.md`）：

```json
{
  "server": "http://192.168.1.10:18080",
  "stateFile": "./xtlog-client.state",
  "collectors": [
    { "name": "订单服务", "token": "应用Token", "appId": 12, "type": "file",
      "paths": ["/var/log/order/app.log"], "encoding": "utf8" },
    { "name": "游戏服务", "token": "应用Token", "appId": 13, "type": "cmd",
      "command": "journalctl -u game.service -f -o short-iso" }
  ]
}
```

要点：

- `collectors` 是数组，**每个元素对应 xtlog 里的一个应用**（填该应用的 token）；
- 客户端会在本地解析级别 / 日志时间 / logger / 线程后再上报，界面显示与服务端自采一致；
- 自动探测本机 IP 作为 `srcIp`（取「去往服务端」的出口 IP，可避开 WSL/Docker 虚拟网卡），不准可在配置里写死；
- 配了 `stateFile` 后文件读取偏移会落盘，重启不重复也不丢日志；
- 只支持 `http://`，需要 https 时在 xtlog 前挂 nginx 做 TLS 终结。

## 6. 运维提示

- **会话存在进程内存中**，服务重启后已登录用户需重新登录，属预期。
- **日志写入是异步批量的**（约 500ms 一批），进程被 `kill -9` 会丢掉最后一批；请用 `bin/stop.sh` / `bin/stop.bat` 走优雅停机。
- SQLite 数据不要放在网络盘（NFS / SMB）上，WAL 依赖文件锁。
- 月表按 `log_time` 分区，历史查询由服务端自动 UNION 月表，无需手工维护。
- 若要长期留存，定期备份整个 `data` 目录即可（SQLite 记得连 `-wal` / `-shm` 一起备份，或先优雅停机）。
