# xtboot 改造清单（基于逐文件源码核实）

> 核实结论：xtboot v0.0.1 已完整具备以下能力，**xtlog 业务开发无需任何框架改造即可进行**：
> SSE（`SseEmitter` 含心跳/连接数/超时）、`@XtScheduled`（含 `CronExpression`）、三级线程池隔离（dispatch/biz/stream）、
> SPA 路由回退、GZIP、请求体大小限制、Session、统一响应 `{code,message,data}`、全套方言（SQLite/MySQL/PostgreSQL/Oracle/SQLServer/H2）、
> 连接池 url 模式默认自动启用、**`DbClient.batchInsert` 批量单事务**（含 `(table, List<Dict>)` 重载）、`DbClientHolder` 数据源热装配、优雅停机钩子。

## 非阻塞优化项（P1，按需再做）

- `JsonUtils` 反射缓存：高频序列化有损耗。
- `JsonUtils` 日期类型支持：`Date` / `Timestamp` 序列化未处理，会静默返回 null。
- 连接池扩容 / 回收策略补强。
- 统一响应结构细化（当前已支持 `{code,message,data}`）。
