# 工作台基础统计

接口需要 `Authorization: Bearer <token>`，成功返回统一的 `code/message/data`。统计今日采用北京时间 00:00（含）至次日 00:00（不含）。同一响应中的统计从同一个 MySQL 事务快照读取。

| GET 路径 | 角色 | 返回内容 |
|---|---|---|
| `/api/dashboard/overview` | 管理员 | 车辆数、司机数、今日识别和告警统计。可选 `fleetId` 筛选。 |
| `/api/dashboard/vehicles/{id}` | 管理员 | 指定车辆的今日识别、告警和当前整点小时的其他分心事件数。 |
| `/api/dashboard/me` | 司机 | 本人今日识别、告警和最近 5 条告警记录。 |

响应中的 `date` 是统计日期。`overview.fleetSize` 按当前档案归属统计车辆和司机数，含停用档案。历史识别和告警按采集时保存的车队、车辆及司机归属统计。司机接口取登录身份，换车后仍可查到本人记录。最近告警按采集时间、ID 倒序，查询范围可跨日期，保留异常和撤销状态，供页面区分。

- `recognitions` 按识别帧统计，`normalFrames` 为 c0，`distractedFrames` 为 c1–c9，另计 `oodFrames` 和 `unclassifiedFrames`。分心帧数包含识别出了类别但未参与告警判定的帧。
- `recognitions.oodRate` = OOD 帧数 / 总帧数。`behaviors` 中的 `share` = 该类别帧数 / 有效分心帧数。比例为 0–1 的小数，保留最多四位精度，空统计返回 0 和空数组。
- `alerts.smsAlertCount`、`alerts.backlogCount` 分别统计 OPEN 或 CONFIRMED 的 SMS_ALERT、BACKLOG，二者之和等于 `activeViolationCount`。工作台按范围内全部记录聚合，列表分页不影响计数。短信类告警计数仅按告警类型统计。
- 速度未知和已撤销的行为告警分别返回为 `speedUnknownCount` 与 `revokedViolationCount`。
- `currentHourDistractionEvents` 读取告警引擎当前整点小时的其他分心事件累计。例如 14:30 返回 14:00 开始的累计，跨小时后旧计数视为 0。该计数与分心帧数不同。

未登录返回 401，角色无权访问返回 403，非法 ID（包括显式传空的 `fleetId`）返回 400，车队或车辆不存在返回 404。省略 `fleetId` 查询全部车队。

已有数据库部署前执行 `src/main/resources/db/migrations/20261120_dashboard_indexes.sql`，为识别与告警表增加采集时间索引，供全局与车队总览按当天的时间范围查询。脚本可顺序重复执行。新库执行完整 `schema.sql`。评分依旧独立于工作台基础统计。


## 管理员总览的近期告警

`GET /api/dashboard/overview` 返回 `recentAlerts`，最多五条，与总览指标共用同一车队、
同一北京时间自然日和同一个数据库快照。结果按采集时间倒序，同一采集时间按告警 ID 倒序。
完整记录可到告警列表查询。待复核申诉继续按工作台现用的全平台范围展示。
