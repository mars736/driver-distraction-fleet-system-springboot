# 车队运营接口与联调

## 数据库升级

已有第二批数据表的数据库执行 `src/main/resources/db/migrations/20261122_fleet_operations.sql`。新库运行完整 `schema.sql`。第三批新增十张运营表。新接口使用现有 Bearer Token，业务时间统一为北京时间。列表默认第 1 页、每页 20 条，上限 100。

## 排班打卡与运输任务

下表路径以 `/api/operations/workforce` 开头。

| 方法和路径 | 角色与用途 |
|---|---|
| `PUT /shifts` | 管理员给启用司机保存排班 |
| `GET /shifts?start=2026-11-22&end=2026-11-24` | 司机查自己，管理员填写 `driverId` |
| `POST /attendance/in`、`POST /attendance/out` | 司机按班次日期签到、签退 |
| `GET /attendance/summary?start=2026-11-22&end=2026-11-24` | 司机查自己，管理员填写 `driverId` |
| `GET /on-duty` | 管理员查可派任务司机，可按 `fleetId` 及 `page`、`pageSize` 筛选 |
| `GET /status` | 司机查自己，管理员填写 `driverId`。返回 `OFF_DUTY`、`ON_DUTY`、`ON_LEAVE` 或 `ON_TASK` |
| `POST /leaves` | 司机提交请假 |
| `GET /leaves` | 司机查自己，可按 `status` 和页码筛选。管理员可加 `driverId`、`fleetId` |
| `POST /leaves/{id}/decision` | 管理员审批请假 |
| `POST /tasks` | 管理员给当前在岗且已绑定同车队车辆的司机派任务 |
| `GET /tasks` | 司机查自己的任务，管理员可按 `driverId`、`fleetId`、`status` 筛选 |
| `POST /tasks/{id}/departure`、`POST /tasks/{id}/completion` | 任务司机提交出发、完成报到 |
| `POST /tasks/{id}/cancel` | 管理员取消待执行或执行中的任务 |

`PUT /shifts` 示例 `{"driverId":1,"date":"2026-11-22","type":"LATE","startsAt":"2026-11-22T20:00:00","endsAt":"2026-11-23T04:00:00"}`。同一司机每天一个班次，开始日期与排班日期一致。默认早班 08:00–16:00，晚班 16:00–次日 00:00，休息 `REST` 无起止时间。自定义班次可跨午夜，最长 24 小时。已打卡的班次保留原排班。

签到、签退示例 `{"shiftDate":"2026-11-22","position":{"latitude":24.48,"longitude":118.08}}`。可提前两小时签到，出勤统计以排班日期归属打卡，迟到和早退按排班起止时间判断。当前班有效、已签到且未签退，并且当前没有获批请假或执行中任务时，司机可派单。

请假示例 `{"type":"PERSONAL","startsAt":"2026-11-23T08:00:00","endsAt":"2026-11-23T16:00:00","reason":"个人事务"}`。类型支持 `PERSONAL`、`SICK`、`ANNUAL`、`OTHER`，审批正文如 `{"approved":true,"note":"同意"}`。已批准的请假和冲突的运输任务不能重叠。

派任务示例 `{"driverId":1,"vehicleId":11,"type":"运输","description":"送货","startsAt":"2026-11-22T20:00:00","endsAt":"2026-11-24T08:00:00"}`。同一司机或车辆的任务时间不能重叠。司机在计划时段内用 `{"latitude":24.48,"longitude":118.08}` 报到出发，任务执行期间使用任务报到入口，长任务可跨天。任务状态为 `PENDING → RUNNING → COMPLETED`，取消为 `CANCELED`。出勤合计取实际任务覆盖日期与打卡日期的并集，跨天任务和打卡同日只算一次，分别返回任务、打卡、迟到、早退、请假天数。日期范围含起点、不含终点，最长 366 天。

## 保养维修和油耗

下表路径以 `/api/operations/fleet` 开头，限平台管理员。

| 方法和路径 | 用途 |
|---|---|
| `GET /vehicles/{id}/odometer`、`PUT /vehicles/{id}/odometer` | 查询、录入最新里程表读数 |
| `GET /vehicles/{id}/maintenance`、`POST /vehicles/{id}/maintenance` | 查询、新增周期保养计划 |
| `POST /maintenance/{id}/complete` | 登记本周期保养完成并计算下次保养 |
| `GET /vehicles/{id}/repairs`、`POST /vehicles/{id}/repairs` | 查看、新建维修单 |
| `POST /repairs/{id}/transition` | 顺序流转维修单状态 |
| `POST /vehicles/{id}/fuel`、`PUT /fuel/{id}` | 新增、修正加油记录 |
| `GET /vehicles/{id}/fuel?start=2026-11-01&end=2026-12-01` | 分页查询油耗与同类车型基准 |

- 里程示例 `{"odometerKm":15500.5,"measuredAt":"2026-11-22T12:00:00"}`。以人工核对的里程表读数判断里程保养。里程和时间不可倒退。
- 保养计划示例 `{"name":"例行保养","intervalDays":90,"intervalKm":5000,"lastServiceDate":"2026-11-20","lastServiceOdometerKm":10000}`。按日期、按里程可分别使用，任一已到期即提示。如果同时设置两类周期，完成时提交日期和里程。完成示例 `{"cycle":1,"date":"2026-11-22","odometerKm":15500.5}`。客户端提交刚查询到的 `cycle`，重复提交旧周期返回 409。
- 新建维修单填写 `description`（维修项目或故障说明）。维修单依次使用 `REPORTED → ASSIGNED → IN_REPAIR → DONE → ACCEPTED → SETTLED`。派工时正文填写 `assignee`，维修厂填 `shop`，结算时填写 `cost`（免费填 0）。
- 加油示例 `{"fueledAt":"2026-11-22T11:00:00","liters":40.5,"unitPrice":7.25,"odometerKm":15400}`。金额按升数乘单价计算，保留两位小数。百公里油耗用本次加油量除以相邻里程表差值后乘 100。查询的第一条记录可以引用范围外的上一条加油记录。修改或补录历史记录后，查询会按加油时间重新确定相邻区间。
- 同车队同车型的其他车辆在查询日期内至少有 3 个有效油耗区间时，取其平均值。高出均值 25% 标记 `ABNORMAL`。没有上一条、里程差值无效或同车型样本不足分别标记 `NO_PREVIOUS`、`ODOMETER_INVALID`、`INSUFFICIENT_PEERS`。阈值可用 `operations.fuel.deviation` 和 `operations.fuel.min-peer-samples` 配置。

## 证照和期限提醒

- 管理员用 `PUT /api/operations/certificates` 登记或续期，正文如 `{"subjectType":"VEHICLE","subjectId":11,"certificateType":"INSURANCE","expiresOn":"2026-12-20"}`。同一主体、同类证照更新原证照。车辆证照支持 `INSPECTION`、`INSURANCE`、`OPERATING_PERMIT`，司机证照支持 `DRIVER_LICENSE`、`DRIVER_QUALIFICATION`。
- 查询指定主体证照用 `GET /api/operations/certificates?subjectType=VEHICLE&subjectId=11`。管理员用 `GET /api/operations/reminders?fleetId=1&sourceType=CERTIFICATE` 补查提醒，`sourceType` 可省略或设为 `MAINTENANCE`。
- 默认每天北京时间 09:00 扫描。证照提前 30 天提醒，超过到期日进入过期阶段。保养提前 7 天或 500 km 提醒，到达目标日期或里程后进入到期阶段。相同到期日或保养周期的同阶段只生成一次，续期和保养完成后可产生新提醒。调试时可在本地配置 `operations.reminders.cron` 为 `*/10 * * * * *`，每 10 秒扫描一次。
- 新提醒提交后，在管理员的 `GET /api/alerts/stream` 连接收到 `event: operations-reminder`，包含提醒 ID、来源类型、主体和阶段。客户端用提醒列表读取完整日期或目标里程。所有运营写操作由统一操作日志记录。
