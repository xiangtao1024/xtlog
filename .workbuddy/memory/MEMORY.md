# xtlog-server 项目长期约定

## 构建（★ 最重要：Nexus 已挂，必须用临时 settings）

内网 Nexus `192.168.31.106:9903` 长期离线。依赖其实都在本地仓库
`D:/app/software_delop/maven/maven-repo`，但那些依赖的 `_remote.repositories` 记录来源是
`aliyun-public`，而全局 settings 只配了 `xt-maven-local` 镜像 → Maven 不认本地缓存、坚持去远程拉取，
**在线和 `-o` 离线都会失败**。`mvn -llr` 在 Maven 3.9.9 已废弃。

**正确命令（项目根执行，必须显式设 JAVA_HOME，否则 mvn.cmd 报「JAVA_HOME not defined correctly」）：**

```bash
export JAVA_HOME='D:/app/software_delop/java/graalvm-jdk-17'
/d/app/software_delop/maven/apache-maven-3.9.9/bin/mvn.cmd -s _mvn-settings.xml -DskipTests clean package
```

`_mvn-settings.xml` 已放在项目根：localRepository 指向同一目录，镜像 id 改为 `aliyun-public`
指向 `https://maven.aliyun.com/repository/public`。产物 `target/xtlog-server.jar`（shade fat jar）。

- mvn / java 都不在 PATH，必须用绝对路径（mvn 是 `.cmd`，java 是 `.exe`）。
- 只改前端时也要走完整打包（mvn 会把 `src/main/resources/static` 打进去），
  不要再用手写 python 替换 `static/` 条目的旁路了——已不需要。
- `xtboot` 是 `mvn install` 本地安装的，永远可用、不依赖远程。

## 客户端子项目（client/，Rust）

- `xtlog-client`：放在被采集机器上，按 `config.json` 采集本机文件/命令日志并 HTTP 推给服务端。
  用法 `./xtlog-client --config=config.json`。**远程机器采日志首选它**（来源 IP 才准）。
- 依赖只有 serde/serde_json/chrono/encoding_rs/ctrlc；网络层是纯标准库实现，**只支持 http://**。
- 构建：`cd client && cargo build --release`（约 25s）。
  **cargo 在 Git-Bash 的 /tmp 路径下会挂**，要在 Windows 路径下跑，必要时 `--offline`。
- **Windows 上无法交叉编译 Linux 版**（缺 ELF 链接器 + lld 相关 flag 是 unstable），
  Linux 二进制请在目标机上 `cargo build --release` 或 `./build.sh`。
- 行解析规则与服务端 `LogTextIngestor` 保持一致，改一端记得同步另一端。

## 临时文件约定

- **不要把验证用的临时文件写到项目根**（接口响应 dump、构建日志、探针源码等），统一写 `/tmp` 或跑完即删。
  2026-10-09 已清理过一次：项目根曾堆了 179 个 `_` 开头的临时文件。
- 根目录现在只应有：`.gitignore`、`pom.xml`、`_mvn-settings.xml`、`_server_run.log`。
- 沙箱的 safe-delete 钩子会拦删除，所以 `.gitignore` 里保留了 `_*.log/_*.txt/_*.json/_*.java` 的忽略规则。

## 前端

- `cd web && npm run build` → 输出到 `../src/main/resources/static`（vite 配置），再由 mvn 打进 jar。
- 改完前端**必须**再跑一次上面的 mvn 打包，否则服务里还是旧的。

## 启动 / 停止（本机开发）

```bash
java -Dxtlog.data.dir=E:/workspace/xt/xtlog-server/data -jar target/xtlog-server.jar > _server_run.log 2>&1
```

- 端口 **18080**，数据目录 `E:/workspace/xt/xtlog-server/data`（sqlite `xtlog.db` + 月表）。
- 停服务：先 `netstat -ano | grep :18080` 找 PID，再
  `/c/Windows/System32/taskkill.exe //PID <pid> //F`（Git-Bash 里 `taskkill` 别名是坏的，必须全路径 + `//`）。
- **出包前必须先停服务**，否则 `dist/` 下的 jar 被 java 占用，copy 会 PermissionDenied 甚至写成 0 字节。

## 访问与调试

- 入口 URL 是 **`/static/index.html`**（`/static/` 目录本身 404）。
- 环境有 `http_proxy`，curl 打 localhost 必须加 **`--noproxy '*'`**。
- 默认账号 `admin / admin123`（**不是** `admin123`，那是旧 session 的误记；生产构建不预填，开发态由 `web/.env.development` 提供）。
- agent-browser 在本机不稳（snapshot/screenshot 常 SIGTERM、element ref 跨调用丢失），
  **API 级验证（curl/python urllib）比浏览器自动化可靠得多**，优先用它做端到端断言。

## 代码约定

- 后端**不用 Lombok**，实体/DTO 手写 getter/setter；字段多为 public 字段（如 `AppService.AppInfo`）。
- 日志按月分表 `xt_log_entry_YYYYMM`，建表/改列走 `DataSourceConfig`
  （`ensureLogTable` + `addColumnIfMissing`，后者用 PRAGMA 探列，兼容老库）。
- 前端级别样式类由 `levelClass()` 返回 **`dg / warn / pri / dbg / info`**（不是 ERROR/WARN…），写 CSS 选择器要注意。
- 采集源类型：`HTTP` / `FILE` / `CMD`。FILE 与 CMD 共用 `LogTextIngestor`（多行合并、时间戳解析、级别推断）；
  HTTP 走 `IngestController`（上报端自带 `srcIp`、级别、logger 等结构化字段）。

## 告警模块（v0.0.2，2026-10-27 上线）

- 表：`xt_alert_rule`（规则定义）+ `xt_alert_event`（事件流），均由 `DataSourceConfig.ensureAlertTables()` 建。
  事件表冗余存 rule_name / app_name，规则或应用被删后事件流仍可读。
- 接口：`/api/alert/rules`（CRUD + toggle + `/test`）与 `/api/alert/events`（列表 + `/ack` + `/resolve`），
  均在 `AlertController`，SQLite 单写者撞锁统一走 `DbBusyRetry.retry(..., 8, 200L)`。
- 引擎：`AlertEngine.scan()` 用 `@XtScheduled(fixedRate=30000)` 每 30 秒扫一次。
  4 种规则类型：LEVEL / KEYWORD / REGEX（拉 1000 候选行 Java 侧 Pattern 过滤）/ RATE。
  评估逻辑是 `public static MatchResult evaluate(holder, appService, rule)`，
  Controller 的 `/test` 直接复用，保证「测试」与「实际触发」条件一致。
- **xtboot 的 `@RequestBody` 不支持 `required` 属性**（不像 Spring），写 `@RequestBody(required = false)` 会编译失败；
  可选 body 的处理方式：强制 `@RequestBody Dict body` + 内部 `body == null` 兜底。
- `DbBusyRetry.retry` 抛 checked Exception；在「失败只记日志、不抛出」的场景下
  用 helper 包裹（如 AlertEngine 的 `tryRetry(desc, action)`）而不是裸调用。

## 应用启用/停用 + 删除清日志（2026-10-28）

- `app` 表新增 `enabled` 列（INTEGER，1=启用默认 / 0=停用），存量 NULL 视为启用。
- 停用语义（用户需求）：HTTP 推送直接丢（`IngestController` 返回 `disabled:true, accepted:0`，不落库不广播）；
  CMD/FILE 采集器 `reconcile`/`scan` 跳过已停用应用（不启动、已起则停掉进程）；历史日志保留可查。
  启用后恢复正常。采集器巡检周期 CMD 5s / FILE 2s，状态变更最多等一个周期生效。
- 接口：`PUT /api/apps/{id}/enable` body `{enabled:0|1}`；前端 `AppView` 有「状态」列 + 启用/停用按钮。
- 删除应用会**连带清空该应用所有月表日志**（含 FTS 孤儿索引项），不再留孤儿。关键顺序：
  `IngestQueue.removeByApp(id)`（丢在途）→ `AppController.deleteAppLogs(id)`（清库）→ 删 app 行 → `refresh()`。
- 落库兜底：`LogBatchWriter` 落库前用 `AppService.resolveById` 判活，应用已删的日志直接丢弃，
  与上面的顺序共同关闭「删完库行又被落库线程写回复活」的并发竞态（`AppService` 索引现已 volatile 原子替换）。
- 验证：隔离实例 e2e 通过——删除后月表 count=0；停用推送 `accepted=0` 库不增，启用后恢复。

## 来源 IP（src_ip）

- FILE / CMD = xtlog **所在机器的 IP**（`util/HostAddresses.localIp()`，打分优选 + 缓存）；
- HTTP = 上报端通过 `srcIp` **字段自己带过来**（不传则留空，不用 TCP 连接地址冒充当机器 IP）。
- **不能「取第一个网卡」**：WSL2/Hyper-V 虚拟交换机（`172.27.0.1`）、Docker 网桥（`172.17.0.1`）
  常排在真实网卡前。现按 网段 → 网卡（MAC/虚拟名/isVirtual）→ 出网路由 打分选最优。
- 自动判断不准时可手动指定：`-Dxtlog.host.ip=` 或环境变量 **`XTLOG_HOST_IP`**（最优先）。
- 多候选时启动会打印 `[xtlog] host ip: x (nif), candidates [...]`（ASCII，避免 Windows GBK 控制台乱码）。

## 性能（2026-10-21 实测，已修复）

- **摄入队列是「有界 + 静默丢弃」的致命设计**：`IngestQueue` 原是 `ArrayBlockingQueue(20000)`，
  且 `offer` 在队列满时对非 ERROR 日志**直接 drop 不报错**。采集端 130k/s 灌入、落库只有 ~2000/s，
  实测 10万条能丢 ~8万，而接口仍返回 `accepted=100000`。**已改为**：容量 200000；非 ERROR 在队列满时
  `offer(log, 200ms)` 做短暂背压，超时仍满才丢弃；`IngestController` 的 `/api/logs` 响应新增
  `queueSize` 与 `droppedTotal` 如实暴露积压与累计丢弃。**结论：落库必须快到能跟上采集，否则靠缓冲也兜不住。**
- **落库写者吞吐瓶颈**：原 `@XtScheduled(fixedRate=500) + drainTo(1000)` 硬卡在 ~2000/s；即便改大，
  瓶颈在 `DbClient.batchInsert` 的逐行 `entityToColumnMap` + 反射 `setParameters`，以及**逐批 prepare/commit/close**。
  裸 xerial 驱动用「同连接 + 同 PreparedStatement + 1000/txn」能到 13万/s，APP 却只有 ~1.6万/s。
  **已优化 `LogBatchWriter`**：专用写连接 + 按表缓存 PS + **每周期单次 commit**（一个周期最多 2万条，250ms 一拍），
  实时采集（时间戳顺序）落库实测 **~10万/s**；随机时间戳（回填场景）会随表增大变慢（SQLite 索引随机插入固有特性），属预期。
- **FTS 全文索引值得开**：查询 `count` 冷 47ms vs 关 FTS 的 LIKE count 485ms（10×）；搜索 22ms vs 28ms（略快）。
  回填慢（10万条 ~66s，~1500/s）但是后台、不阻塞采集（落库先于回填完成）。
