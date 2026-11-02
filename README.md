# xtlog-server

**单机轻量统一日志台：开箱即用、数据不出门、不挑日志格式。**

一个 jar 跑起来，建个应用、配个路径，日志就能看了。**不是 ELK 的功能子集，而是 `tail -f` + 多个 SSH 窗口的替代品**——给那些被 ELK 劝退、又不想把日志塞给 SaaS 的中小团队 / 个人开发者。

| | Seq / Papertrail / Better Stack | xtlog-server |
|---|---|---|
| 部署 | SaaS 或单机二进制，多数要走外网 | 单 jar + SQLite，512M 内存能跑，**数据完全在本机** |
| 上手 | 注册账号 / 装 agent | 建应用、配路径，点开就能看 |
| 采集方式 | 上报为主 | HTTP / FILE / CMD / Rust 客户端 四种 |
| 日志格式 | 多需 agent 适配 | **不挑格式**，Java / nginx / syslog / 任意文本通吃 |
| 告警 | 商业版才有或限额 | **内置规则引擎 + Webhook**（v0.0.2） |
| 对象 | 个人开发者 / 小团队 | 中小团队 / 个人 / 多语言小项目群 / 老服务器 |

**一句话**：让一个同时跑着 SpringBoot、Flask、Express 的人，5 分钟内搭起统一日志台，还能在 ERROR 突增时打到钉钉 / 飞书。

核心取舍：**单机优先、零外部依赖、数据不出门**。所有重能力（集群、链路追踪、Grafana 数据源）都不做——它们和你无关时，单机就是最优解。

---

## 何时选 xtlog-server

- 你的日志分布在 1～5 台机器上，没有专职运维
- 你不想把日志推到 SaaS（合规、成本、网络不稳）
- 你被 ELK / Graylog 的 4G+ 内存、Lucene DSL、索引调优劝退过
- 你的服务器是老的 1C2G VPS、树莓派、嵌入式 Linux
- 你想给 Nginx / journalctl / Spring Boot 一个**统一的查看入口 + 告警**

**不适合**：日志量 > 50GB/天、需要分布式集群、需要 Grafana 数据源协议、需要完整链路追踪体系。这种场景请上 ELK / Loki / OpenObserve。

---

## 功能特性

- **四种日志来源**
  - `HTTP` —— 应用主动上报（JSON，单条/批量），跨语言通用
  - `FILE` —— 服务端按字节偏移增量跟随日志文件，支持轮转与多行堆栈合并
  - `CMD` —— 服务端常驻执行一条 shell 命令，把输出当日志流（如 `journalctl -u game.service -f -o short-iso`）
  - `xtlog-client` —— 独立二进制，装在**别的机器**上采集后推送过来（远程采集场景）
- **不挑日志格式**：文本日志通吃，**不限于 Spring Boot / Logback**。内置多格式时间戳识别（Java/ISO8601、`nginx error` 的 `2026/10/10 09:15:32`、`nginx access` 的 `[10/Oct/2026:09:15:32 +0000]`、syslog 的 `Oct 10 09:15:32` 等），能正确切分条目、提取级别与时间，Nginx 等日志每行成为独立记录而不会糊成一团
- **实时跟随**：SSE 长连接推送，日志控制台块状布局（时间/级别/logger/IP 一行 + message 换行 + 级别色条），支持暂停、清屏、全屏
- **历史检索**：时间窗 + 级别 + 关键字 + 应用，表格分页，双击看详情（含多行堆栈、traceId、来源 IP）
- **来源 IP**：每条日志记录来源机器，微服务多实例场景下能分清是哪台机器打的
- **分类管理**：树形分类，按业务线/环境组织应用
- **零外部依赖**：自带 SQLite 驱动，默认 `data/xtlog.db`（WAL）；需要 MySQL / PostgreSQL 时丢个驱动 jar 进 `drivers/` 即可
- **按月分表**：日志表 `xt_log_entry_YYYYMM`，历史查询自动 UNION，无需手工维护
- **告警规则与事件中心（v0.0.2）**：按级别 / 关键词 / 正则 / 速率定义触发条件，配评估窗口、阈值、静默时长与 Webhook；触发后产生事件、自动恢复、支持认领，事件页 30 秒自动刷新

---

## 告警

xtlog 内置规则引擎，每 30 秒扫描一次启用的规则，在评估窗口内对日志月表聚合匹配，命中数 ≥ 阈值时生成事件并触发 Webhook。事件在静默窗口内自动合并（避免反复告警），命中数降到阈值一半以下时自动标记恢复。

### 规则类型

| 类型 | 表达式 | 说明 |
|---|---|---|
| `LEVEL` | `ERROR` 或 `ERROR,WARN` | 多级逗号分隔，按 `level` 字段精确匹配 |
| `KEYWORD` | 任意文本 | 按 `message LIKE '%kw%'` 模糊匹配 |
| `REGEX` | Java 正则（如 `\bTimeoutException\b`） | 先按时间窗拉候选行（最多 1000 条），Java 侧用 `Pattern` 过滤；为兼容各库正则函数差异做的折衷 |
| `RATE` | 级别词（如 `ERROR`） | 与 LEVEL 同语义，配短窗口（如 60s）+ 高阈值表达「N 次/分钟」 |

### 关键参数

| 参数 | 默认 | 说明 |
|---|---|---|
| 评估窗口 `window_sec` | 300 秒 | 引擎每次扫描覆盖最近 N 秒的日志 |
| 触发阈值 `threshold` | 1 | 窗口内命中数 ≥ 该值即触发 |
| 静默时长 `silence_sec` | 600 秒 | 触发后该时长内合并到同一事件，不重复告警 |
| 作用应用 `app_id` | 全部 | 0/空 表示作用于所有应用，否则只评估指定应用 |
| 通知渠道 `channels` | — | JSON 形式，目前支持 `{"webhook":"https://..."}`，后续扩展钉钉 / 飞书 / 邮件 |

### 接入示例

在「告警规则」页新建规则，或直接调 API：

```bash
# 创建规则：订单服务 ERROR 频繁告警，5 分钟内 ≥ 10 条触发，触发后静默 30 分钟
curl -X POST http://localhost:18080/api/alert/rules \
  -H "Content-Type: application/json" \
  -H "satoken: <登录会话 token>" \
  -d '{
    "name":"订单服务 ERROR 告警",
    "appId":12,
    "ruleType":"LEVEL",
    "ruleExpr":"ERROR",
    "windowSec":300,
    "threshold":10,
    "silenceSec":1800,
    "channels":"{\"webhook\":\"https://oapi.dingtalk.com/robot/send?access_token=xxx\"}"
  }'
```

### Webhook payload

触发时以 POST JSON 通知配置的地址，字段含 `event_id` / `rule_id` / `rule_name` / `app_id` / `app_name` / `level` / `hit_count` / `threshold` / `window_sec` / `summary` / `sample_message`（截断到 1000 字）。可直接转给钉钉 / 飞书 / 企业微信 / Slack 的入站机器人。

### 事件生命周期

```
新建（status=1 触发中）
  ↓ 30s 后再次命中：静默窗口内 → 合并（更新 hit_count / last_time / 样本）
  ↓ 静默窗口过期：旧事件标记恢复(status=2)，新事件触发并再发 Webhook
  ↓ 命中数 < threshold/2：自动标记恢复(status=2)
  ↓ 人工认领：status=3 已认领
```

事件可手动认领、手动标记恢复，也可在「告警事件」页 30 秒自动刷新查看最新状态。

---

## 界面

| 首页概览 | 应用管理 |
| --- | --- |
| ![首页](docs/screenshots/11-dashboard.png) | ![应用管理](docs/screenshots/12-apps.png) |

| 日志控制台（实时 + 全部） | 日志详情 |
| --- | --- |
| ![日志控制台](docs/screenshots/13-logs-console.png) | ![日志详情](docs/screenshots/35-log-detail.png) |

| 命令采集器配置 | 开发配置（接入示例） |
| --- | --- |
| ![命令采集器](docs/screenshots/42-cmd-collector.png) | ![开发配置](docs/screenshots/18-dev-config.png) |

---

## 快速开始（直接用部署包）

从 `dist/` 取 `xtlog-server-0.0.1.zip`（或执行打包脚本自己生成），解压即可用：

```bash
unzip xtlog-server-0.0.1.zip && cd xtlog-server-0.0.1

# Windows
bin\start.bat          # 前台，Ctrl+C 结束
bin\start-bg.bat       # 后台，PID 写进 data\xtlog.pid
bin\stop.bat

# Linux / macOS
chmod +x bin/*.sh
bin/start.sh
```

- 访问 **http://localhost:18080**
- 默认管理员 `admin / admin123`（**首次登录后请在「个人中心」改密码**）
- Linux 生产环境推荐 systemd 托管：`sudo cp service/xtlog.service /etc/systemd/system/ && sudo systemctl enable --now xtlog`

> 完整的部署、配置、运维说明见 **[deploy/README.md](deploy/README.md)**。

---

## 从源码运行 / 开发

### 环境

- JDK 8+（源码按 1.8 编译，推荐 JDK 17 运行）
- Maven 3.6+、Node.js 18+
- **xtboot 0.0.1**（自研零依赖框架，是本项目的后端底座，需先安装到本地 Maven 仓库）

### 构建

```bash
# 1) 前端（产物直接输出到 src/main/resources/static，mvn package 时自动进 jar）
cd web
npm install
npm run build          # 开发调试时用 npm run dev（端口 3000，已代理 /api 到 18080）

# 2) 后端 fat jar
cd ..
mvn -s _mvn-settings.xml -DskipTests clean package
# 产出 target/xtlog-server.jar（包含后端 + 前端静态资源 + 全部依赖）
```

> `_mvn-settings.xml` 是内网/离线环境用的 settings（本地仓库 `D:/app/software_delop/maven/maven-repo` + aliyun 镜像）。
> 若你的 Nexus/中央仓库可直连，直接 `mvn -DskipTests clean package` 即可。

### 运行

```bash
java -jar target/xtlog-server.jar
# 控制台出现 "started on port: 18080" 后访问 http://localhost:18080
```

常用环境变量：

| 变量 | 说明 |
| --- | --- |
| `XTLOG_DATA_DIR` | 数据目录（默认安装目录下 `data/`） |
| `XTLOG_HOST_IP` | 手动指定「本机 IP」，FILE/CMD 采集写入日志的 `src_ip`（多网卡判断不准时用） |
| `JAVA_OPTS` | JVM 参数，如 `-Xmx1g` |

### 组装部署包

```bash
sh scripts/build-dist.sh      # Linux / macOS
scripts\build-dist.bat        # Windows
# 产出 dist/xtlog-server-<ver>/ 与压缩包
```

---

## 目录结构

```
xtlog-server/
├── src/main/java/top/xtcoder/xtlog/    # 后端（Java + xtboot）
│   ├── XtLogApplication.java           # 启动入口（@XtBootApplication）
│   ├── config/                         # 数据源、表结构迁移、初始化鉴权
│   ├── controller/                     # App / Category / Log / Ingest / Auth / Stats / Setup
│   ├── ingest/                         # 采集与落库：FileCollector、CommandCollector、
│   │                                   #   LogTextIngestor、IngestQueue、LogBatchWriter、LogBroadcast
│   ├── service/                        # AppService、UserService
│   └── util/HostAddresses.java         # 本机 IP 打分优选（避开 WSL/Docker 虚拟网卡）
├── src/main/resources/static/          # 前端构建产物（vite outDir，不要手改）
├── web/                                # 前端（Vue 3 + Element Plus + Vite）
│   └── src/views|components|api|store|router|styles
├── client/                             # 子项目：xtlog-client（Rust，远程采集推送器）
├── deploy/                             # 部署骨架：bin / config / drivers / service / README
├── scripts/                            # 部署包组装脚本
├── docs/                               # 规划方案、xtboot 改造清单、截图
├── dist/                               # 打包产出（gitignore）
└── data/                               # 运行期数据（SQLite 库，gitignore）
```

---

## 接入日志

### 1. HTTP 上报（推荐，跨语言通用）

在「应用管理」新建应用拿到 **token**：

```bash
curl -X POST http://localhost:18080/api/logs \
  -H "Content-Type: application/json" \
  -d '{"token":"<应用token>","level":"ERROR","logger":"com.svc.Gateway","message":"网关超时","srcIp":"10.0.0.21"}'
```

批量上报用 `logs: [...]` 数组。字段：`token`、`level`、`logger`、`thread`、`traceId`、`srcIp`、`timestamp`（毫秒，**建议传**，决定落在哪个月表）、`message`。

### 2. FILE / CMD（服务端主动采）

在应用编辑页选来源类型，填文件路径或命令即可。注意：这两种采集发生在 **xtlog 服务所在机器**。

### 3. xtlog-client（别的机器上的日志）

```bash
./xtlog-client --config=config.json
```

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

`collectors` 是数组，每项对应 xtlog 里的一个应用。客户端本地解析级别/时间/logger/线程后再上报，界面显示与服务端自采一致；`stateFile` 记录**文件采集**的读取偏移（仅 `file` 采集器使用），重启不重复也不丢日志，相对路径按启动时的工作目录解析。

> 完整字段说明见 **[client/README.md](client/README.md)**；远程采集的三种方案选型见 [deploy/README.md §5.2](deploy/README.md)。

### 关于「来源 IP」

| 采集方式 | `src_ip` 来源 |
| --- | --- |
| HTTP | 上报端通过 `srcIp` 字段自带（微服务各实例填本机 IP） |
| FILE / CMD | xtlog 服务所在机器的 IP |
| xtlog-client | 被采集机器的 IP（自动探测出口 IP，可在配置里写死） |

FILE/CMD 的「本机 IP」由 `HostAddresses` **打分优选**得出（网段 192.168 > 10 > 172.16~31，虚拟网卡/WSL/Docker 关键字扣分，出网路由命中加分），避免拿到 `172.27.0.1`（WSL）这类地址。启动时控制台会打印选中结果与候选得分，也可通过 `XTLOG_HOST_IP` 或 `-Dxtlog.host.ip=` 手动指定。

---

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Java 8+，自研零依赖框架 **xtboot**（NIO HTTP / ORM / 调度 / 配置），maven-shade 打 fat jar |
| 前端 | Vue 3（`<script setup>`）+ Element Plus + Vite，产物挂在 `/static` 下由后端托管 |
| 存储 | SQLite（默认，WAL，按月分表）；MySQL / PostgreSQL 可选（驱动丢 `drivers/`） |
| 实时 | SSE（`/api/logs/stream`） |
| 客户端 | Rust（serde / chrono / encoding_rs），单二进制，零运行时依赖 |

主要接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/logs` | 日志上报（单条 / 批量） |
| GET | `/api/logs` | 日志检索（时间窗、级别、关键字、分页） |
| GET | `/api/logs/stream` | 实时日志 SSE |
| GET/POST/PUT/DELETE | `/api/apps` | 应用管理 |
| GET/POST/PUT/DELETE | `/api/categories` | 分类管理 |
| GET | `/api/stats/overview` | 概览统计 |
| POST | `/api/auth/login` · `/api/auth/logout` | 登录 / 登出 |
| GET/POST | `/api/setup` | 初始化/数据源配置 |

---

## 运维提示

- 会话存在进程内存中，服务重启后需重新登录（预期行为）。
- 日志写入是异步批量的（约 500ms 一批），`kill -9` 会丢最后一批，请走 `bin/stop.*` 优雅停机。
- SQLite 数据**不要放网络盘**（NFS/SMB），WAL 依赖文件锁。
- 备份直接拷整个 `data` 目录（连 `-wal` / `-shm` 一起，或先优雅停机）。
- 改了前端/后端后，浏览器需 **Ctrl+F5** 强刷才能看到新资源。

---

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [deploy/README.md](deploy/README.md) | 部署、systemd、配置、接入、远程采集、来源 IP、运维 |
| [client/README.md](client/README.md) | xtlog-client 用法与全部配置字段 |
| [docs/xtlog-server-规划方案.md](docs/xtlog-server-规划方案.md) | 产品定位、功能矩阵、架构设计 |
| [docs/xtboot-改造清单.md](docs/xtboot-改造清单.md) | 自研框架 xtboot 的能力改造记录 |

## 路线图 / 待办

- [x] **告警规则与事件中心**（v0.0.2：级别 / 关键词 / 正则 / 速率，评估窗口 + 阈值 + 静默 + Webhook + 认领/恢复）
- [ ] 日志删除与导出 API
- [ ] journald `-o json` 的 PRIORITY → 级别映射
- [ ] xtlog-client 支持 https（当前需 nginx 做 TLS 终结）
- [ ] xtlog-client 的 Linux 预编译二进制与 systemd 守护示例
- [ ] 告警渠道扩展：钉钉 / 飞书 / 企业微信机器人模板消息
- [ ] 告警规则模板库（常见场景：ERROR 频次、超时堆栈、5xx 风暴）
