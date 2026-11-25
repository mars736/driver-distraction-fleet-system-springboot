# 实时告警订阅

使用 `GET /api/alerts/stream` 请求 SSE 流，带 `Authorization: Bearer <token>` 和 `Accept: text/event-stream`。接入方可使用支持自定义请求头的 SSE 客户端。原生 EventSource 可通过下文的票据接口建立连接。

- 服务端发出 `event: ready` 后表示订阅已经注册。之后每个 `event: alert` 的 `id` 为告警 ID，`data` 包括 `id`、`driverId`、`vehicleId`、`fleetId`、北京时间 `capturedAt`、`recordType` 和 `behaviorCode`。详情仍从 `GET /api/alerts/{id}` 获取。
- 管理员能收到全部已提交的新告警，司机只收到归属自己的告警。事务回滚、同一帧重试、告警复核和短信状态变更均不发送新告警事件。
- 每条告警发出前会校验当前账号状态、角色及令牌版本，空闲时约 5 秒校验一次并发送心跳注释。连接最长 60 秒，令牌早于此时间到期时按令牌时间关闭。未登录返回 401，其他角色返回 403，连接容量用尽返回 429。
- 客户端收到 `ready` 后调用原告警列表重新加载并按需分页，同时接收流上的事件，两边按告警 ID 合并去重。断开后携带有效令牌重新请求，重复这一步，覆盖离线期间已落库的记录。不要仅以最后一条采集时间作为补查下界，延迟写入的旧帧可能早于这个时间。
- 每个后端进程最多 32 个流，每个账号最多 4 个流，每个流积压达到 32 条后再有事件入队时关闭该流，让客户端重连补查。事件队列在内存中，后端不使用 `Last-Event-ID` 重放。多实例部署时，当前版本只推送连接所在进程产生的事件，可用列表补查。

## 原生 EventSource

1. 用 Bearer Token 请求 `POST /api/alerts/stream-ticket`，无需请求体。响应 `data.ticket` 是一次性票据，`data.expiresAt` 是 UTC 到期时间。票据最长有效 30 秒，且不晚于 JWT 到期。
2. 使用 `GET /api/alerts/stream?ticket=<ticket>` 建立连接。这条请求可以省略 Authorization。票据只用于此路径，首次使用时核销并校验当前账号、角色和令牌版本。过期、重复使用或账号失效返回 401。每个账号最多保留 4 张未使用的票据。
3. 流断开后调用 `EventSource.close()`，稍后重新申请票据并新建连接。原生 EventSource 自动重试会沿用旧 URL，因此接入方需要在错误处理里关闭它。申请票据时收到 401 先恢复登录，403 停止重试，429 退避后再请求。
4. 收到 `ready` 后补查遗漏告警，并按告警 ID 与流上事件合并去重。票据只决定建立连接的时限，连接仍受原有 60 秒上限及 JWT 过期时间约束。

```js
async function connectAlerts(apiBase, accessToken, { onAlert, onReady, onDisconnect }) {
  const response = await fetch(`${apiBase}/api/alerts/stream-ticket`, {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!response.ok) throw new Error(`SSE ticket request failed: ${response.status}`);
  const { data } = await response.json();
  const stream = new EventSource(`${apiBase}/api/alerts/stream?ticket=${encodeURIComponent(data.ticket)}`);
  stream.addEventListener("ready", onReady); // 在回调中按需分页补查
  stream.addEventListener("alert", (event) => onAlert(JSON.parse(event.data)));
  stream.onerror = () => {
    stream.close();
    onDisconnect(); // 由调用方退避后再次调用 connectAlerts
  };
  return () => stream.close();
}
```

票据存储在当前后端进程内。多实例部署时，申请票据与订阅请求需路由到同一实例。使用 HTTPS，并在访问日志和监控中省略订阅查询串。

## 请求示例

以下命令使用 Bearer 请求头订阅。

```powershell
curl.exe -N -H "Authorization: Bearer <token>" -H "Accept: text/event-stream" http://localhost:8080/api/alerts/stream
```

使用现有告警记录表，无需数据库迁移。本阶段只提供在线页面的实时事件。

平台管理员还可收到 `event: vehicle-offline`，详见[车辆定位与车速上报](vehicle-telemetry.md)。
