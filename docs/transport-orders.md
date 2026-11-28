# 运输订单与派单联调

本批后端支持管理员先登记订单，再指定司机及其已绑定车辆。订单派发后生成运输任务，司机 App 沿用原任务接口完成报到。初次部署旧库时，在现有迁移之后执行 `src/main/resources/db/migrations/20261128_transport_orders.sql`。接口响应使用现有 `Result` 包装。

## 管理员 Web

将原任务列表纳入「运输订单与派单」的运输任务页签，并在订单页提供建单、候选和确认派单。排班、请假和出勤使用原接口。

以下地址以 `/api/operations/transport-orders` 为前缀，限平台管理员。

| 方法和路径 | 功能 |
|---|---|
| `POST /` | 创建待派订单，司机和车辆由派单时确定 |
| `GET /` | 分页查看，可按 `fleetId`、`status`、`requirement` 筛选 |
| `GET /{id}` | 详情，含当前任务和完整的 `assignments` |
| `PUT /{id}` | 编辑待派订单，请求使用完整建单字段并带 `expectedVersion` |
| `GET /{id}/candidates` | 分页查询同车队司机、绑定车辆和当前候选资格 |
| `POST /{id}/assign` | 选择司机及车辆，创建运输任务 |
| `POST /{id}/cancel` | 取消待派订单，请求带 `expectedVersion` |

列表和候选读取 `data.list`、`total`、`page`、`pageSize`。候选按可选优先、司机 ID 升序排列，`eligible=false` 时禁用确认按钮。`safetyRating` 在本批响应中为空。高要求订单可建单及查看候选，确认派发时返回 `RATING_UNAVAILABLE`，等待长期评级模块提供可用结果。

建单示例：

```json
{
  "fleetId": 1,
  "taskType": "器械运输",
  "description": "运送两箱器械并交接",
  "originAddress": "集美区示例路 10 号 A 库",
  "destinationAddress": "湖里区示例路 20 号收货口",
  "startsAt": "2030-12-03T08:00:00",
  "endsAt": "2030-12-03T12:00:00",
  "requirement": "NORMAL"
}
```

两端地址必填，每项最多 255 字。计划时间按北京时间本地日期时间提交。`requirement` 省略时为 `NORMAL`，高要求为 `HIGH`。新单初始 `version=1`，编辑、取消订单或成功派发后递增。

派发的请求头带 `Idempotency-Key`，同一动作遇到超时请用同一标识与内容重试。

```json
{
  "driverId": 18,
  "vehicleId": 42,
  "expectedVersion": 1
}
```

成功返回 `orderId`、`taskId`、`status` 和 `version`。收到响应后刷新订单详情。即使原任务后来取消或已经重派，同一请求的重放仍返回当次派发结果。

| 订单状态 | 操作 |
|---|---|
| `WAITING_DISPATCH` 待派单 | 编辑、查候选、确认派发、取消订单 |
| `ASSIGNED` 待出发 | 查看当前任务或取消本次任务 |
| `IN_TRANSIT` 运输中 | 查看进度 |
| `COMPLETED` 已完成、`CANCELED` 已取消 | 查看详情 |

取消待出发的任务使用 `POST /api/operations/workforce/tasks/{id}/cancel`。取消后原订单回到待派单。编辑地址并重新派发会新建一条任务，旧任务保留当时的地址。任务进度按司机报到和任务状态即时读取。

HTTP 409 时检查 `data.reason`：

| 原因 | 处理方式 |
|---|---|
| `ORDER_VERSION_CONFLICT`、`ORDER_NOT_DISPATCHABLE` | 刷新订单，重新核对状态 |
| `ORDER_REQUEST_KEY_REUSED` | 核对原派发结果，新的派发使用新标识 |
| `DRIVER_NOT_ELIGIBLE` | 查看消息并刷新候选，重新选司机与车辆 |
| `RATING_UNAVAILABLE` | 提示暂无已发布长期评级 |

## 司机 App

继续调用 `GET /api/operations/workforce/tasks`、`GET /api/operations/workforce/tasks/page`、`GET /api/operations/workforce/tasks/{id}`。出发与完成分别调用 `POST /api/operations/workforce/tasks/{id}/departure` 和 `POST /api/operations/workforce/tasks/{id}/completion`，继续提交 `latitude` 与 `longitude`。

任务响应现在包含 `orderId`、`orderNo`、`originAddress`、`destinationAddress`、`requirement`。地址为计划地点，司机定位字段表示实际报到位置。历史任务的订单号和地址可为空，页面可显示「未登记」。管理员取消后重派会生成新任务 ID，请按任务 ID 刷新列表和状态。

[OpenAPI JSON](./api/openapi.json) · [离线接口文档](./api/index.html)
