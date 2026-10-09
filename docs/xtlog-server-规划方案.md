# xtlog-server 项目规划方案 v2

> 轻量统一日志服务：一处接入，多处来源，集中查看与分析。
> 后端：Java + xtboot（自研零依赖框架）｜前端：Vue 3 + Element Plus
> 存储：SQLite 默认，MySQL / PostgreSQL 可选
> 文档位置：`E:\workspace\xt\xtlog-server\docs\xtlog-server-规划方案.md`
> **xtboot 源码：`E:\workspace\xt\xtboot\xtboot`（注意两层 xtboot），包名 `top.xtcoder.xtboot`，groupId `top.xtcoder`，artifactId `xtboot`，v0.0.1，JDK 8**

> v2 更新：摸清 xtboot 真实能力后重写第八章，修订第九、十一章，新增 JDK 8 约束说明。

---

## 一、产品定位

对标 ELK / Graylog / Loki 的**轻量单机替代品**。

| 维度 | ELK 全家桶 | xtlog-server |
|---|---|---|
| 部署 | ES + Logstash + Kibana，内存 4G 起 | 单个 jar + 一个 db 文件，512M 能跑 |
| 上手 | 要学 Lucene 语法 | 建个应用、配个路径，点开就能看 |
| 对象 | 中大型团队专业运维 | 中小团队 / 个人开发者 / 多语言小项目群 |
| 定位 | 平台 | 工具 |

**一句话**：让一个同时跑着 SpringBoot、Flask、Express 的人，5 分钟内搭起统一日志台。

核心取舍：**单机优先、零外部依赖、开箱即用**。所有重能力（全文检索、集群、链路追踪）都必须能在单机范围内降级或后置。

---

## 二、需求拆解与范围

### 2.1 已确认决策

| 项目 | 结论 |
|---|---|
| xtboot | 源码可控（`E:\workspace\xt\xtboot\xtboot`），边做业务边补能力 |
| 存储 | SQLite 默认 + MySQL/PG 可选，分片 + 索引 |
| 查看体验 | WebSocket 实时跟随（tail -f）+ 历史游标回溯 |
| 首版范围 | 全功能一次性做全（含告警、统计大盘） |

### 2.2 功能矩阵

| 模块 | 能力 | 优先级 |
|---|---|---|
| 初始化引导 | 首次访问进 Setup 向导：选库类型、填连接、测连通、建表、建管理员 | P0 |
| 分类管理 | 树形分类，按业务线/环境组织 | P0 |
| 应用管理 | CRUD、上报 Token、采集源配置、启停、保留策略 | P0 |
| HTTP 接入 | 批量 JSON 上报 + 纯文本多行上报，Token 鉴权 | P0 |
| 文件采集 | 目录/文件监听、断点续读、多行堆栈合并、轮转兼容 | P0 |
| 日志检索 | 时间窗 + 级别 + 关键字 + 应用/来源，游标翻页 | P0 |
| 实时跟随 | WebSocket 订阅推送，暂停/恢复，虚拟滚动 | P0 |
| 统计分析 | 概览卡片、趋势曲线、级别分布、Top 报错聚类 | P1 |
| 告警 | 关键字/级别/频率规则、静默窗口、Webhook/邮件 | P1 |
| 系统设置 | 保留策略、清理任务、连接信息、采集状态监控 | P1 |
| 多语言接入 | SpringBoot / Flask / Express 参考实现 | P1 |

### 2.3 明确不做（二期）

- 分布式集群、分片复制
- 完整链路追踪体系（只支持 traceId 透传与跳转检索）
- Grafana 数据源协议

---

## 三、总体架构

```
┌───────────── 日志来源 ─────────────┐
│ SpringBoot   Flask    Express      │
│ (Logback)  (logging) (winston)     │
└───────┬─────────┬──────────┬───────┘
        │ HTTP    │ HTTP     │ 已有日志文件（被动监听）
        ▼         ▼          ▼
┌───────────────────────────────────────────────┐
│            xtlog-server（单 jar）              │
│  ┌─────────────┐   ┌──────────────────────┐  │
│  │ HTTP Ingest │   │  File Tail Collector │  │
│  │  Controller │   │  (WatchService+offset)│  │
│  └──────┬──────┘   └──────────┬───────────┘  │
│         ▼                     ▼              │
│  ┌────────────────────────────────────────┐  │
│  │   LogPipeline：解析 → 规整 → 有界队列    │  │
│  └──────┬──────────────────────┬──────────┘  │
│         ▼                      ▼             │
│  ┌────────────┐      ┌──────────────────┐   │
│  │ BatchWriter│◄────►│ ShardStorage     │   │
│  │ (批量落库) │      │ 月表/分区 + 方言 │   │
│  └──────┬─────┘      └──────────────────┘   │
│         ├──► LogBroadcaster ──► SSE / WS    │
│         ├──► AlertEngine ──► Webhook/邮件   │
│         └──► StatAggregator ──► 预聚合表     │
└───────────────────────────────────────────────┘
                     ▲ REST / SSE
                     ▼
        ┌──────────────────────────┐
        │  Vue3 + Element Plus 控制台│
        └──────────────────────────┘
```

### 3.1 三条主线

1. **写入线**：接入 → 解析规整 → 有界内存队列（带背压）→ 批量 JDBC 写入 → ack
2. **消费线**：历史查询走 ShardStorage 游标分页；实时走 SSE/WS 订阅推送
3. **旁路**：告警评估、5 分钟预聚合、过期清理 —— 全部异步，失败不影响入库

### 3.2 关键设计决策

| 决策 | 理由 |
|---|---|
| 写入全异步 + 有界队列 | 洪峰不能拖垮 HTTP 线程；队列满则采样丢弃并计数 |
| 按月分片 + 方言抽象 | xtboot 已有 DialectResolver + SqliteDialect，可直接复用扩展 |
| 游标翻页（ts + id） | 日志是时序数据，offset 深分页必然劣化 |
| SSE 优先于 WebSocket | xtboot 零依赖，WS 要手写 RFC6455；SSE 本质是长连接 HTTP，成本差一个数量级，先用它跑通 tail -f |
| alert / stat 旁路异步 | 不做同步拦截 |
| 前端产物打进 jar | xtboot 已有 StaticResourceHandler，天然支持 |

---

## 四、数据模型

### 4.1 组织与配置表

```sql
-- 分类（树形）
CREATE TABLE xt_category (
  id           BIGINT PRIMARY KEY,
  parent_id    BIGINT       DEFAULT 0,
  name         VARCHAR(64)  NOT NULL,
  sort_no      INT          DEFAULT 0,
  remark       VARCHAR(255),
  create_time  BIGINT
);

-- 应用
CREATE TABLE xt_app (
  id             BIGINT PRIMARY KEY,
  category_id    BIGINT       NOT NULL,
  name           VARCHAR(64)  NOT NULL,
  code           VARCHAR(64)  NOT NULL UNIQUE,   -- 英文标识
  app_type       VARCHAR(32),                    -- springboot/flask/express/other
  token          VARCHAR(64)  NOT NULL UNIQUE,   -- HTTP 上报凭证
  env            VARCHAR(32),                    -- dev/test/prod
  status         TINYINT      DEFAULT 1,
  retention_days INT          DEFAULT 7,
  create_time    BIGINT,
  update_time    BIGINT
);
CREATE INDEX idx_app_cat ON xt_app(category_id);

-- 日志源（一个应用可配多个：HTTP 或 FILE）
CREATE TABLE xt_log_source (
  id               BIGINT PRIMARY KEY,
  app_id           BIGINT       NOT NULL,
  name             VARCHAR(64),
  source_type      VARCHAR(16)  NOT NULL,        -- HTTP / FILE
  enabled          TINYINT      DEFAULT 1,
  -- FILE 专用
  file_path        VARCHAR(512),
  file_pattern     VARCHAR(128) DEFAULT '*.log',
  charset          VARCHAR(16)  DEFAULT 'UTF-8',
  multiline        TINYINT      DEFAULT 1,
  scan_interval_ms INT          DEFAULT 1000,
  follow_mode      VARCHAR(16)  DEFAULT 'tail',  -- tail(只读增量) / whole(从头读)
  -- 解析规则
  parse_type       VARCHAR(16)  DEFAULT 'auto',  -- auto/json/pattern/line
  pattern_regex    VARCHAR(512),
  time_format      VARCHAR(64),
  -- 采集位点
  last_offset      BIGINT       DEFAULT 0,
  last_inode       VARCHAR(64),
  last_size        BIGINT       DEFAULT 0,
  last_time        BIGINT,
  last_error       VARCHAR(512)
);
CREATE INDEX idx_src_app ON xt_log_source(app_id, enabled);
```

### 4.2 日志主表（按月分片）

表名规则：`xt_log_entry_YYYYMM`

```sql
CREATE TABLE xt_log_entry_202601 (
  id           BIGINT       NOT NULL,
  ts           BIGINT       NOT NULL,   -- 事件时间(ms)
  recv_ts      BIGINT       NOT NULL,   -- 服务端接收时间(ms)
  category_id  BIGINT,
  app_id       BIGINT       NOT NULL,
  source_id    BIGINT,
  level        TINYINT      NOT NULL,   -- 0 TRACE 1 DEBUG 2 INFO 3 WARN 4 ERROR 5 FATAL
  trace_id     VARCHAR(64),
  span_id      VARCHAR(64),
  thread       VARCHAR(64),
  logger       VARCHAR(255),
  message      TEXT,
  exception    TEXT,                    -- 堆栈单独存，前端折叠展示
  host         VARCHAR(64),
  instance     VARCHAR(64),
  tags         TEXT,                    -- JSON
  raw          TEXT,                    -- 原始行，兜底用
  msg_hash     VARCHAR(32)              -- message 归一化 hash，Top 报错聚类用
);
CREATE INDEX idx_q_app_ts  ON xt_log_entry_202601(app_id, ts DESC, id DESC);
CREATE INDEX idx_q_cat_ts  ON xt_log_entry_202601(category_id, ts DESC);
CREATE INDEX idx_q_level   ON xt_log_entry_202601(app_id, level, ts DESC);
CREATE INDEX idx_q_trace   ON xt_log_entry_202601(trace_id);
CREATE INDEX idx_q_hash    ON xt_log_entry_202601(app_id, msg_hash);
```

**分片适配策略**

| 数据库 | 策略 |
|---|---|
| SQLite | 无原生分区 → 按月建独立表 + 写入路由 + 过期整表 DROP（无 vacuum 压力） |
| MySQL | `PARTITION BY RANGE COLUMNS(ts)` |
| PostgreSQL | 声明式分区 `PARTITION BY RANGE (ts)` |

```java
public interface ShardManager {
    String resolveTable(long ts);
    void ensureShard(long ts);
    void purgeExpired(int retentionDays);
    List<String> tablesInRange(long from, long to);
}
```

### 4.3 检索加速

SQLite 启用 **FTS5** 虚表做全文检索（sqlite-jdbc 自带 FTS5，不用额外引依赖）：`xt_log_fts(message, exception, logger, app_id UNINDEXED)` + rowid 关联主表。MySQL 走 FULLTEXT（ngram），PG 走 `tsvector`。

```java
public interface LogSearcher {
    List<Long> search(long appId, String keyword, long from, long to, int limit);
}
// 三级降级：FTS5/FULLTEXT → LIKE '%kw%' → 应用层过滤
```

### 4.4 告警与统计表

```sql
CREATE TABLE xt_alert_rule (
  id          BIGINT PRIMARY KEY,
  app_id      BIGINT,          -- 为空表示作用于分类或全局
  category_id BIGINT,
  name        VARCHAR(64),
  rule_type   VARCHAR(16),     -- LEVEL / KEYWORD / REGEX / RATE
  rule_expr   VARCHAR(512),
  window_sec  INT    DEFAULT 300,
  threshold   INT    DEFAULT 1,
  silence_sec INT    DEFAULT 600,
  channels    TEXT,            -- JSON: webhook / email / dingtalk / feishu
  enabled     TINYINT DEFAULT 1
);

CREATE TABLE xt_alert_event (
  id         BIGINT PRIMARY KEY,
  rule_id    BIGINT,
  app_id     BIGINT,
  level      TINYINT,
  summary    VARCHAR(512),
  sample_id  BIGINT,           -- 触发的那条日志
  hit_count  INT,
  status     TINYINT,          -- 1 触发中 2 已恢复 3 已认领
  first_time BIGINT,
  last_time  BIGINT
);

-- 5 分钟预聚合，避免每次开大盘都扫主表
CREATE TABLE xt_stat_bucket (
  bucket_ts BIGINT,
  app_id    BIGINT,
  level     TINYINT,
  cnt       BIGINT,
  PRIMARY KEY (bucket_ts, app_id, level)
);
```

---

## 五、核心链路实现要点

### 5.1 HTTP 上报（Push）

```http
POST /api/v1/ingest/{appToken}
Content-Type: application/json
```

```json
{
  "source": "order-service-1",
  "entries": [
    {
      "ts": 1780000000123,
      "level": "ERROR",
      "logger": "c.x.order.OrderService",
      "thread": "http-nio-8080-exec-3",
      "message": "create order failed",
      "exception": "java.lang.NullPointerException\n\tat ...",
      "traceId": "7f3a91c2",
      "host": "10.0.0.7",
      "tags": { "env": "prod" }
    }
  ]
}
```

响应：`{"accepted": 12, "dropped": 0, "queueDepth": 34}`

兼容性：同时支持 `Content-Type: text/plain` 的裸文本行提交，服务端按 JSON → 正则 → 纯文本三级自动识别。**任何语言都能用 curl 直接怼进来**。

鉴权：URL Token + 可选 HMAC 签名。

### 5.2 文件采集（Pull）

每个 FILE 源一个采集任务，状态机：

```
[发现文件] → 匹配 pattern → 首次按 follow_mode 决定 offset(0 或 EOF)
    ↓
[读取] → RandomAccessFile.seek(offset) → 读到 EOF → 记 newOffset
    ↓
[轮转检测] → inode 变化 或 size < lastSize → 判定 truncate/rotate
    ↓          → 重置 offset=0，从新文件头读
[多行合并] → 不匹配新日志起始特征的行，追加至上一条的 exception
    ↓
[ACK 位点] → 每 N 条或 1s 刷一次 last_offset/last_inode/last_size
```

多行起始判定（auto）：行首是 `{` / 匹配常见时间前缀（`2026-01-01`、`[2026-01-01`、`2026-01-01T10:00`）/ 命中自定义 `pattern_regex`。

### 5.3 写入管线与背压

```
HTTP/File → Parser → LogEvent（规整）
                        ↓ offer
        ArrayBlockingQueue<>(capacity = 20000)
                        ↓ drain(maxBatch=500, maxWait=200ms)
                    BatchWriter
                    ├─ JDBC batchInsert
                    ├─ 失败重试 2 次 → 落 .bad 文件，绝不丢线程
                    └─ 成功 → publish(event)
                                ├─ Broadcaster(SSE/WS)
                                ├─ AlertEngine
                                └─ StatAggregator
```

背压：队列 > 80% 按比例采样丢弃 DEBUG/TRACE，**ERROR 永不丢**，丢弃数计入指标。宁可掉日志，不能把服务拖死。

### 5.4 实时跟随

- 连接：`GET /api/v1/logs/stream?token=...`，返回 `text/event-stream`（SSE）
- 客户端先用 appId + 过滤条件订阅，服务端按会话过滤后下发
- 单会话最多缓冲 5000 条，超出裁剪并提示「已跳过 N 条」
- 前端：跟随开关默认开，用户上滚自动暂停并显示「↓ 回到最新」

> 若后期确实需要双向能力与更强吞吐，再补 WebSocket。**先把 SSE 跑通**。

### 5.5 历史查询

```json
POST /api/v1/logs/query
{
  "appIds": [1001],
  "levels": [3, 4, 5],
  "keyword": "Timeout",
  "startTime": 1780000000000,
  "endTime":   1780003600000,
  "cursor": "1780001234567_998877",
  "size": 100,
  "sort": "desc"
}
```

返回：`{ "list": [...], "nextCursor": "...", "hasMore": true }`

> **不给 total 精确值** —— 千万级 `count(*)` 是自杀行为。用 `hasMore` 驱动继续加载，总量走预聚合表。

---

## 六、REST API 清单

| 分组 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 引导 | GET | `/api/v1/setup/status` | 是否已初始化 |
| 引导 | POST | `/api/v1/setup/test` | 测试数据库连接 |
| 引导 | POST | `/api/v1/setup/init` | 建库建表 + 建管理员 + 热切 DataSource |
| 认证 | POST | `/api/v1/auth/login` `/logout` | 会话 Token |
| 分类 | GET/POST/PUT/DELETE | `/api/v1/categories[/id]` | 树形 CRUD |
| 应用 | GET/POST/PUT/DELETE | `/api/v1/apps[/id]` | CRUD |
| 应用 | POST | `/api/v1/apps/{id}/token/reset` | 重置上报 Token |
| 源 | GET/POST/PUT/DELETE | `/api/v1/apps/{id}/sources[/sid]` | 采集源管理 |
| 源 | POST | `/api/v1/apps/{id}/sources/{sid}/toggle` | 启停采集 |
| 日志 | POST | `/api/v1/logs/query` | 游标分页检索 |
| 日志 | GET | `/api/v1/logs/{id}` | 单条详情 |
| 日志 | GET | `/api/v1/logs/agg` | 趋势聚合（预聚合表） |
| 实时 | GET(SSE) | `/api/v1/logs/stream` | 订阅推送 + 心跳 |
| 告警 | CRUD | `/api/v1/alert-rules` | 规则管理 |
| 告警 | GET | `/api/v1/alert-events` | 事件列表 + 认领 |
| 统计 | GET | `/api/v1/stats/overview` | 概览卡片 |
| 统计 | GET | `/api/v1/stats/top-errors` | Top 报错（msg_hash 聚类） |
| 系统 | GET/PUT | `/api/v1/system/config` | 保留策略等 |
| 系统 | GET | `/api/v1/system/health` | 队列深度、采集状态、写入速率 |

---

## 七、前端设计（Vue 3 + Element Plus）

| 页面 | 路由 | 关键组件 |
|---|---|---|
| 初始化向导 | `/setup` | 三步表单：选库类型 → 填连接（SQLite 只需文件路径）→ 建管理员；「测试连接」实时反馈 |
| 登录 | `/login` | — |
| 主框架 | `/` | 左侧「分类树 + 应用列表」双栏，顶栏全局搜索/级别过滤/环境切换 |
| 概览大盘 | `/dashboard` | 4 张卡片 + 24h 趋势面积图 + 级别占比环图 + Top10 报错表 |
| 应用管理 | `/apps/:id` | Tab：基本信息 / 采集源 / **接入指南**（动态给出对应语言配置片段）/ 保留策略 |
| **日志查看** | `/apps/:id/console` | **核心页**，见下 |
| 告警规则 | `/alerts/rules` | 规则表格 + 抽屉编辑器（可视化条件构造）+ 测试触发 |
| 告警事件 | `/alerts/events` | 事件流、恢复标记、批量认领 |
| 系统设置 | `/system` | 队列/采集状态、清理任务、存储占用 |

### 日志查看页（重点打磨）

```
┌── 工具条：时间快捷 ▾ │ 级别 ▾ │ 关键字 🔍 │ ⏸跟随 │ 密度 ▾ │ 导出 ┐
├──────────────────────────────────────────────────────┤
│ 09:31:22.101 [ERROR] c.x.o.OrderService - create fail │ ← 虚拟滚动（万行不卡）
│ 09:31:22.098 [WARN ] c.x.o.StockClient  - retry 1/3   │    级别色带 + 等宽字体
│ 09:31:21.870 [INFO ] c.x.o.OrderService - begin       │
├──────────────────────────────────────────────────────┤
│ ↓ 回到最新（暂停跟随时浮出）                            │
└──────────────────────────────────────────────────────┘
  点击任一行 → 右侧抽屉：完整消息 / 堆栈折叠 / tags / raw / traceId 跳转
```

技术要求：`vue-virtual-scroller` 保证万行流畅；暗色终端风格 + JSON/Java 堆栈语法高亮；SSE 断线自动重连并按 lastTs 增量补拉。

---

## 八、xtboot 现状盘点（v2 核心更新）

源码根目录：`E:\workspace\xt\xtboot\xtboot`，包名 `top.xtcoder.xtboot`。
框架定性：**零第三方依赖**（pom 的 `<dependencies>` 为空），纯 JDK 8 原生实现，配套 mdBook 文档在 `E:\workspace\xt\xtboot\doc`（含 SUMMARY.md 全量目录）。

### 8.1 已经具备（直接可用）

| 能力 | 位置 / 关键类 |
|---|---|
| IoC 容器 | `context/annotation`：`@XtBootApplication` `@ComponentScan` `@Component` `@Service` `@Repository` `@Autowired` `@Configuration` `@Bean` |
| 配置系统 | `@Value`、`env/ConfigLoader`、`@Config` + `@Inject` + `ConfigProcessor`（配置类绑定） |
| 嵌入式 HTTP Server | `web/server/EmbeddedHttpServer` + `DispatcherHandler` |
| 路由与参数绑定 | `@RestController` `@Controller` `@RequestMapping` `@GetMapping/PostMapping/PutMapping/DeleteMapping`、`@RequestParam` `@PathVariable` `@RequestBody` `@ResponseBody` |
| **ORM 全套** | `orm/DbClient` + 链式 `Query` + `Cnd` + `Dict` 风格 + `BaseEntity` + SQL 模板 `SqlTemplateLoader` + 建表 `create/drop/struct` + `FieldValueAdaptor` + `FieldConfig` + `QueryPager` |
| **方言抽象** | `orm/dialect`：`DialectResolver` + `SqliteDialect` / `H2Dialect` / `SqlServerDialect` / `OracleDialect` / `LimitOffsetDialect` |
| **连接池** | `orm/pool/PooledConnection`（已有 PoolTest） |
| **事务** | `orm/tx/TransactionManager` + `@Transactional` |
| JSON 序列化 | `utils/JsonUtils`（自研，含 JsonUtilsTest） |
| 参数校验 | `NotNull` `NotBlank` `NotEmpty` `Size` `Min` `Max` `Email` `Pattern` `Valid` |
| 全局异常处理 | `@ControllerAdvice` + `@ExceptionHandler` |
| **静态资源** | `web/staticresource/StaticResourceHandler` → 前端 dist 直接丢进 resources/static 就能跑 |
| 文件上传 | `MultipartFile` / `MultipartParser` |
| 模板引擎（自研） | `template` 包 + Lexer + BuiltInFunctions |
| 工具类 | `ClassScanner` `StringUtils` `core/Dict` `banner/Banner` |

> **结论：规划草稿里担心的「框架薄」完全不成立。** 路由、IoC、ORM、连接池、事务、方言、静态资源、校验 —— 这些是 M0 的绝大部分工作量，现在全省了。

### 8.2 真正的缺口（只剩 4 项）

| # | 缺口 | 为什么必须有 | 建议做法 |
|---|---|---|---|
| 1 | **动态数据源热装配** | Setup 向导填完库信息后要能直接切 DataSource，不能要求用户重启 | `DbClientBuilder.setDataSource()` 已留口子；需支持运行时重建 DbClient 并重绑单例。注意先关闭旧 `PooledConnection` 池 |
| 2 | **定时任务** | 文件采集扫描、5 分钟预聚合、过期清理、队列 flush 都要用 | 自研 `@XtScheduled` + ScheduledExecutorService 调度器（约 200 行），零依赖下没有别的选择 |
| 3 | **SSE 流式响应** | 实时跟随 tail -f | EmbeddedHttpServer 需支持「不关闭的输出流 + chunked 流式写」。先做 SSE，WS 后补 |
| 4 | **JsonUtils 能力校验** | 自研 JSON 要扛住：嵌套 Map/List、泛型反序列化、`Map<String,Object>` 的 tags 字段、日期格式、转义（日志正文里什么字符都有） | 先写一轮针对性单测，缺什么补什么 |

### 8.3 可能后期补充

- WebSocket（RFC6455 握手 + 帧编解码，纯手写约 400-600 行）—— 只在 SSE 吞吐不够时再做
- 优雅停机钩子（停机前 flush 队列、保采集 offset）
- 拦截器 / Filter（鉴权、请求日志）—— 若已有则忽略

### 8.4 零依赖的边界问题（需你拍板）

xtboot 本身零依赖是个很漂亮的取舍，但**业务侧无法回避第三方驱动**：

```xml
<!-- xtlog 业务模块引入，框架层依然保持零依赖 -->
<dependency>
  <groupId>org.xerial</groupId>
  <artifactId>sqlite-jdbc</artifactId>
  <version>3.42.0.0</version>
</dependency>
```

建议原则：**框架层继续零依赖，驱动/工具类依赖只出现在 xtlog 业务模块**。MySQL / PG 驱动同理。

### 8.5 JDK 8 约束（**决策已定：统一 JDK 8**）

> 用户已拍板选用 JDK 8。xtboot 维持 `target 1.8` 不动，xtlog 业务模块跟随，完整编码禁忌见 `xtboot-改造清单.md` 第五章。

- ❌ 不能用 `var`、`record`、文本块、`List.of()`、`Map.of()`
- ❌ 不能用 JDK 11+ 的 `java.net.http.HttpClient`（告警的 Webhook 调用要用 `HttpURLConnection` 或自封装）
- ✅ 可用 lambda、Stream、`Optional`、`CompletableFuture`
- ✅ 文件路径用 `java.nio.file`（WatchService、`Path`）
- 如果业务侧确实用到需要更高 JDK 的特性，**先决定是否统一升级 xtboot 到 JDK 17**（这是个前置决策，越早定越好）

---

## 九、工程结构

xtboot 作为**外部独立仓库**引用，不混进 xtlog 的业务目录。

```
xtboot/（外部仓库，路径固定）
└── E:\workspace\xt\xtboot\xtboot\        # mvn install 到本地仓库后按 top.xtcoder:xtboot:0.0.1 引用

xtlog-server/（业务仓库）
├── xtlog-common/          # 工具类、常量、统一响应
├── xtlog-domain/          # 实体（@Table/@Column）、枚举、仓储接口
├── xtlog-ingest/          # 管线：Parser / Queue / BatchWriter / Broadcaster
├── xtlog-collector/       # 文件 Tail 采集
├── xtlog-alert/           # 规则引擎 + 通知渠道
├── xtlog-stat/            # 预聚合 + 大盘查询
├── xtlog-admin/           # Controller + 鉴权
├── xtlog-start/           # 启动器、配置、fat jar 打包
│   └── src/main/resources/static/   # 前端构建产物
├── xtlog-webui/           # Vue3 + Element Plus
│   └── src/{api,views,components,stores,router,styles}
└── docs/
    ├── schema/{sqlite,mysql,postgresql}.sql
    ├── 接入指南.md          # SpringBoot / Flask / Express 示例
    └── api.md
```

**打包**：前端 `vite build` → 拷入 `xtlog-start/src/main/resources/static`，产出单个可执行 jar：

```bash
java -jar xtlog.jar --xt.data.dir=./data
```

> 工程要不要放在 `E:\workspace\xt\doubao\demo01`？那里现在是 xtexam 项目，建议给 xtlog 一个独立目录（如 `E:\workspace\xt\xtlog-server`，就是当前工作区）。**这条待你确认。**

---

## 十、多语言接入方式

| 平台 | 接入方式 | 交付物 |
|---|---|---|
| SpringBoot | 自定义 Logback `AppenderBase` / Log4j2 Appender，异步批量 POST | `xtlog-logback` starter |
| Flask | 自定义 `logging.Handler` + `MemoryHandler` 缓冲批量发 | 单文件 `xtlog_handler.py`，复制即用 |
| Express | Winston Transport / Pino Transport | `xtlog-winston` npm 包 |
| 任意语言 | 裸 HTTP POST JSON（附 curl 示例） | 通用文档 |

应用详情页「接入指南」Tab **按 appType 动态渲染对应语言的代码片段，并把 token 直接填好** —— 这是降低上手成本的关键细节。

---

## 十一、开发里程碑（已按 xtboot 现状重算）

原 M0 需要补 5 项框架能力，现在只剩 4 个缺口，且都不在关键路径最前面 —— **整体进度可以前移**。

| 阶段 | 目标 | 可验证产出 |
|---|---|---|
| **M0 骨架** | 业务工程搭起来 + 依赖 xtboot + 补 `JsonUtils` 单测 | 启动成功，`GET /health` 返回 ok，能查到一条库记录 |
| **M1 数据底座** | 方言扩展（MySQL/PG）+ 建表 + Setup 向导 + **热切数据源** | 空库启动 → 浏览器进向导 → 选 SQLite → 建库 → 登录成功，**全程不重启** |
| **M2 HTTP 接入** | Ingest API + 管线 + 批量写 + 补 `@XtScheduled` | curl 打 1000 条，库里能查到，延迟 < 1s |
| **M3 文件采集** | Tail + 位点 + 多行合并（依赖定时任务） | 配一个正在写入的 .log，日志自动进来；轮转后不丢不重 |
| **M4 管理端** | 分类树 + 应用 CRUD + 源配置 | 建 3 个应用，改配置即时生效 |
| **M5 日志检索** | 游标查询 + 过滤 + 详情抽屉 + FTS5 | 百万行下翻页无卡顿 |
| **M6 实时跟随** | **SSE 流式响应**（EmbeddedHttpServer 增强） | 两个浏览器同时看，刷得像 tail -f |
| **M7 告警** | 规则引擎 + Webhook + 事件中心 | 注入 ERROR → 10s 内收到通知 → 事件页可见 |
| **M8 大盘** | 预聚合 + 图表 | 24h 趋势、级别分布、Top10 报错 |
| **M9 收尾** | 三种语言 SDK + 单包部署 + 压测 | 单 jar 启动，持续写入 1 小时不掉链子 |

建议节奏：先打通 **M0–M2 最小闭环**（依赖 xtboot → Setup 向导 → curl 打日志 → 页面看到一条）。这条通了，后面都是往上挂东西。

---

## 十二、风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| **SQLite 写锁竞争** | `database is locked` | 强制 WAL + `busy_timeout=5000` + 全局只有 BatchWriter 一个线程写库 |
| **自研连接池未经历高并发** | 连接泄漏 / 死锁 | BatchWriter 单写者大幅降低并发压力；压测阶段重点盯泄漏，必要时给 PooledConnection 加归还检测 |
| **日志洪峰打爆内存** | OOM | 有界队列 + 采样丢弃 + 队列深度指标 |
| **磁盘被日志吃满** | 服务不可用 | 保留策略默认 7 天 + 磁盘水位 >85% 触发紧急清理最老分片 |
| **采集位点丢失导致重复/漏采** | 数据不准 | offset 每秒落库；二次校验 inode + size + 前 128 字节 hash |
| **JsonUtils 能力不足** | 日志正文转义失败、tags 解析异常 | M0 先把单测补齐，别等到 M2 才发现 |
| **EmbeddedHttpServer 不支持流式写** | SSE 做不了 | M6 前先做技术验证（最小 demo 验证长连接 + chunked）；若不支持，改用轮询保底 |
| **中文/编码乱码** | 内容不可读 | 源级 charset 可配 + auto 探测（UTF-8 → GBK 回退） |
| **Web 端万行卡顿** | 体验崩 | 虚拟滚动 + 客户端 5000 条上限 |

---

## 十三、默认值建议

| 配置项 | 默认值 |
|---|---|
| 服务端口 | `9009` |
| 数据目录 | `./data`（SQLite 文件 `xtlog.db`） |
| 队列容量 | `20000` |
| 批量写入 | `500` 条 / `200ms`（先到先写） |
| 日志保留 | `7` 天（按应用可覆盖） |
| 实时缓冲 | 每会话 `5000` 条 |
| 采集扫描间隔 | `1000ms` |
| 预聚合粒度 | `5` 分钟 |

---

## 十四、开工前需要拍板的 3 件事

1. **JDK 版本**：xtboot 目前 target 1.8。是保持 JDK 8 一路到底，还是统一升到 JDK 17？（越早定越好，后期改成本很高）
2. **业务仓库落址**：建议 `E:\workspace\xt\xtlog-server`（当前工作区），跟 xtboot 平级；`doubao\demo01` 现在是 xtexam，不适合混放。
3. **先验证哪个风险点**：我建议先做两个 30 分钟的技术验证 ——
   - SSE 在 EmbeddedHttpServer 上能不能跑（阻塞 M6）
   - `JsonUtils` 扛不扛得住复杂嵌套与转义（阻塞 M2）

要不要先动这两项验证？
