# 实时告警订阅

使用 `GET /api/alerts/stream` 请求 SSE 流，带 `Authorization: Bearer <token>` 和 `Accept: text/event-stream`。浏览器端用可设置请求头的 `fetch` 读流，司机 App 使用支持自定义请求头的 SSE 客户端。

- 服务端发出 `event: ready` 后表示订阅已经注册。之后每个 `event: alert` 的 `id` 为告警 ID，`data` 包括 `id`、`driverId`、`vehicleId`、`fleetId`、北京时间 `capturedAt`、`recordType` 和 `behaviorCode`。详情仍从 `GET /api/alerts/{id}` 获取。
- 管理员能收到全部已提交的新告警，司机只收到归属自己的告警。事务回滚、同一帧重试、告警复核和短信状态变更均不发送新告警事件。
- 每条告警发出前会校验当前账号状态、角色及令牌版本，空闲时约 5 秒校验一次并发送心跳注释。连接最长 60 秒，令牌早于此时间到期时按令牌时间关闭。未登录返回 401，其他角色返回 403，连接容量用尽返回 429。
- 客户端收到 `ready` 后调用原告警列表重新加载并按需分页，同时接收流上的事件，两边按告警 ID 合并去重。断开后携带有效令牌重新请求，重复这一步，覆盖离线期间已落库的记录。不要仅以最后一条采集时间作为补查下界，延迟写入的旧帧可能早于这个时间。
- 每个后端进程最多 32 个流，每个账号最多 4 个流，每个流积压达到 32 条后再有事件入队时关闭该流，让客户端重连补查。事件队列在内存中，后端不使用 `Last-Event-ID` 重放。多实例部署时，当前版本只推送连接所在进程产生的事件，可用列表补查。

示例：

```powershell
curl.exe -N -H "Authorization: Bearer <token>" -H "Accept: text/event-stream" http://localhost:8080/api/alerts/stream
```

使用现有告警记录表，无需数据库迁移。本阶段只提供在线页面的实时事件。

平台管理员还可收到 `event: vehicle-offline`，详见[车辆定位与车速上报](vehicle-telemetry.md)。
