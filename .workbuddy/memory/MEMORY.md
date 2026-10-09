# xtlog-server 项目长期约定

## 构建（★ 最重要：Nexus 已挂，必须用临时 settings）

内网 Nexus `192.168.31.106:9903` 长期离线。依赖其实都在本地仓库
`D:/app/software_delop/maven/maven-repo`，但那些依赖的 `_remote.repositories` 记录来源是
`aliyun-public`，而全局 settings 只配了 `xt-maven-local` 镜像 → Maven 不认本地缓存、坚持去远程拉取，
**在线和 `-o` 离线都会失败**。`mvn -llr` 在 Maven 3.9.9 已废弃。

**正确命令（项目根执行）：**

```bash
mvn -s _mvn-settings.xml -DskipTests clean package
```

`_mvn-settings.xml` 已放在项目根：localRepository 指向同一目录，镜像 id 改为 `aliyun-public`
指向 `https://maven.aliyun.com/repository/public`。产物 `target/xtlog-server.jar`（shade fat jar）。

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
- 默认账号 `admin / admin123`（生产构建不预填，开发态由 `web/.env.development` 提供）。
- agent-browser 在本机不稳（snapshot/screenshot 常 SIGTERM、element ref 跨调用丢失），
  **API 级验证（curl/python urllib）比浏览器自动化可靠得多**，优先用它做端到端断言。

## 代码约定

- 后端**不用 Lombok**，实体/DTO 手写 getter/setter；字段多为 public 字段（如 `AppService.AppInfo`）。
- 日志按月分表 `xt_log_entry_YYYYMM`，建表/改列走 `DataSourceConfig`
  （`ensureLogTable` + `addColumnIfMissing`，后者用 PRAGMA 探列，兼容老库）。
- 前端级别样式类由 `levelClass()` 返回 **`dg / warn / pri / dbg / info`**（不是 ERROR/WARN…），写 CSS 选择器要注意。
- 采集源类型：`HTTP` / `FILE` / `CMD`。FILE 与 CMD 共用 `LogTextIngestor`（多行合并、时间戳解析、级别推断）；
  HTTP 走 `IngestController`（上报端自带 `srcIp`、级别、logger 等结构化字段）。

## 来源 IP（src_ip）

- FILE / CMD = xtlog **所在机器的 IP**（`util/HostAddresses.localIp()`，打分优选 + 缓存）；
- HTTP = 上报端通过 `srcIp` **字段自己带过来**（不传则留空，不用 TCP 连接地址冒充当机器 IP）。
- **不能「取第一个网卡」**：WSL2/Hyper-V 虚拟交换机（`172.27.0.1`）、Docker 网桥（`172.17.0.1`）
  常排在真实网卡前。现按 网段 → 网卡（MAC/虚拟名/isVirtual）→ 出网路由 打分选最优。
- 自动判断不准时可手动指定：`-Dxtlog.host.ip=` 或环境变量 **`XTLOG_HOST_IP`**（最优先）。
- 多候选时启动会打印 `[xtlog] host ip: x (nif), candidates [...]`（ASCII，避免 Windows GBK 控制台乱码）。
