# 违规处置工单联调

已有第三批数据表的库先执行 `src/main/resources/db/migrations/20261123_violation_dispatch.sql`。新库运行完整 `schema.sql`。迁移会为历史有效违规补建工单，重复执行保留已存在的工单和处理记录。新违规告警与工单同事务保存，每条告警对应一张工单。

- `SMS_ALERT` 和 `BACKLOG` 属于建单范围，`c1`、`c2`、`c3`、`c4` 为高危，其他行为为一般。速度未知异常仍可在告警列表查看。
- 车辆、司机、车队、行为、置信度和时间取告警原始归属。位置在建单时固定，使用采集时刻及之前 15 秒内、同一车辆和司机的最新定位。缺坐标时可手动派发。

## 工单和派发接口

以下路径均以 `/api/operations/violation-dispatch` 开头，按现有 JWT 规则鉴权，限平台管理员。平台管理员可以派发、开始和关闭工单。

| 方法和路径 | 用途 |
|---|---|
| `GET /orders` | 分页筛选 `fleetId`、`driverId`、`status`、`risk`，默认第 1 页、每页 20 条 |
| `GET /orders/{id}` | 查看工单、原告警状态、处置人与处置结果 |
| `PUT /operator-position` | 登录的管理员上报自己的位置，正文如 `{"latitude":24.48,"longitude":118.08}` |
| `GET /orders/{id}/candidates` | 查询启用管理员的派发排序，返回距离和未完成工单数 |
| `POST /orders/{id}/assign` | 手动派发，正文如 `{"assigneeId":2}` |
| `POST /orders/{id}/assign-nearest` | 派发给当前排序首位且有有效距离的人选 |
| `POST /orders/{id}/start` | 开始处置 |
| `POST /orders/{id}/close` | 关闭工单，正文如 `{"outcome":"HANDLED","resolution":"已核对并完成安全沟通"}` |
| `GET /orders/{id}/personnel-recommendation` | 查看人员调整建议、有效性及历史决定 |
| `POST /orders/{id}/personnel-recommendation` | 审核建议，正文如 `{"action":"TRAINING","note":"安排参加安全培训"}` |

状态按 `PENDING → ASSIGNED → IN_PROGRESS → CLOSED` 流转。关闭选项为 `HANDLED`、`NO_ACTION`、`REFERRED`，均需填写处理说明。并发操作只允许一次成功，其余请求按当前状态返回 409。告警复核或申诉撤销时，未关闭工单进入 `CANCELED`。已经关闭的工单可继续查看处理记录和最新告警状态。

管理员位置自上报起 10 分钟内可用于排序。默认以直线距离（km）加 `2 × 未完成工单数` 计算排序值。同分按未完成件数及用户 ID 排序。工单或候选人无有效坐标时仍可人工选择。前端按司机定位约定的坐标系上报位置。

## 评分与人员建议

司机评分依照原告警状态计算。工单关闭保存处理结论，告警确认或撤销仍从原来的告警复核和申诉入口完成。告警撤销后，原采集月份的评分依现有规则回补。

已关闭的高危工单在原告警仍为已确认，且司机最近 30 天内累计至少 3 条已确认高危违规时给出人员建议。窗口和阈值可通过 `operations.dispatch.high-risk-window-days`、`operations.dispatch.high-risk-threshold` 配置。管理员可选择 `TRAINING`、`CHANGE_SHIFT`、`STOP_SHIFT` 或 `DISMISSED` 并填写理由。审核后的排班或任务调整通过现有车队运营接口办理。原告警撤销后，已经记录的处理决定仍可追溯。

## 联调顺序

先触发一条违规告警，在工单列表查到对应记录。用两个管理员账号各自上报位置，查看候选排序，按派发、开始、关闭操作。用告警复核或申诉撤销验证工单状态及司机评分。派发、开始、关闭、撤销和人员建议审核均可在统一操作日志中查询。
