# 车队运营接口与联调

## 数据库升级

已有第二批数据表的数据库执行 `src/main/resources/db/migrations/20261122_fleet_operations.sql`。新库运行完整 `schema.sql`。第三批新增十张运营表。已有运营表的数据库按顺序执行 `20261126_workforce_task_attendance.sql`、`20261126_fleet_care_certificates_fuel.sql`，以及 README 列出的后续迁移。新接口使用现有 Bearer Token，业务时间统一为北京时间。列表默认第 1 页、每页 20 条，上限 100。

## 排班打卡与运输任务

下表路径以 `/api/operations/workforce` 开头。

| 方法和路径 | 角色与用途 |
|---|---|
| `PUT /shifts` | 管理员给启用司机保存排班 |
| `GET /shifts/{id}/history` | 管理员查排班版本及变更前后的类型、起止时间和操作者 |
| `GET /shifts?start=2026-11-22&end=2026-11-24` | 司机查自己，管理员填写 `driverId` |
| `POST /attendance/in`、`POST /attendance/out` | 司机按班次日期签到、签退 |
| `GET /attendance/summary?start=2026-11-22&end=2026-11-24` | 司机查自己，管理员填写 `driverId` |
| `GET /on-duty` | 管理员查可派任务司机，可按 `fleetId` 及 `page`、`pageSize` 筛选 |
| `GET /status` | 司机查自己，管理员填写 `driverId`。返回 `OFF_DUTY`、`ON_DUTY`、`ON_LEAVE` 或 `ON_TASK` |
| `POST /leaves` | 司机提交请假 |
| `GET /leaves`、`GET /leaves/page` | 司机查自己，可按 `status` 筛选。管理员可加 `driverId`、`fleetId` |
| `GET /leaves/{id}` | 司机查本人的请假，管理员可查指定记录 |
| `POST /leaves/{id}/decision` | 管理员审批请假 |
| `POST /tasks` | 管理员向运营中的车队派单，司机须在岗且绑定同车队车辆 |
| `GET /tasks`、`GET /tasks/page` | 司机查自己的任务，管理员可按 `driverId`、`fleetId`、`status` 筛选 |
| `GET /tasks/{id}` | 司机查本人的任务，管理员可查指定记录 |
| `POST /tasks/{id}/departure`、`POST /tasks/{id}/completion` | 任务司机提交出发、完成报到 |
| `POST /tasks/{id}/cancel` | 管理员取消待执行任务 |

`PUT /shifts` 示例 `{"driverId":1,"date":"2026-12-01","type":"LATE","startsAt":"2026-12-01T20:00:00","endsAt":"2026-12-02T04:00:00","expectedVersion":0}`。同一司机每天一个班次，开始日期与排班日期一致。默认早班 08:00–16:00，晚班 16:00–次日 00:00，休息 `REST` 无起止时间。自定义班次可跨午夜，最长 24 小时。北京时间今天及以后可保存，已打卡的班次保留原排班。

新建传 `expectedVersion: 0`，编辑传上次读取的 `version`，成功后返回新版本。旧库既有排班从版本 1 开始，迁移前的编辑没有历史记录。历史日期与版本冲突分别返回 HTTP 409 和 `data.reason` 中的 `SHIFT_PAST_DATE`、`SHIFT_VERSION_CONFLICT`。工作班进入已登记的休息日会返回 `SHIFT_OVERLAPS_REST`，休息日覆盖已有工作班会返回 `REST_OVERLAPS_SHIFT`。时段仅在边界相接时可保存。

签到、签退示例 `{"shiftDate":"2026-11-22","position":{"latitude":24.48,"longitude":118.08}}`。可提前两小时签到，出勤统计以排班日期归属打卡，迟到和早退按排班起止时间判断。当前班有效、已签到且未签退，并且当前没有休息安排、获批请假或执行中任务时，运营车队的司机可进入派单候选。

请假示例 `{"type":"PERSONAL","startsAt":"2026-11-23T08:00:00","endsAt":"2026-11-23T16:00:00","reason":"个人事务"}`。类型支持 `PERSONAL`、`SICK`、`ANNUAL`、`OTHER`，审批正文如 `{"approved":true,"note":"同意"}`。已批准的请假与工作班次、冲突的运输任务不能重叠。

派任务示例 `{"driverId":1,"vehicleId":11,"type":"运输","description":"送货","startsAt":"2026-11-22T20:00:00","endsAt":"2026-11-24T08:00:00"}`。同一司机或车辆的任务时间不能重叠。司机在计划时段内用 `{"latitude":24.48,"longitude":118.08}` 报到出发，任务执行期间使用任务报到入口，长任务可跨天。任务状态为 `PENDING → RUNNING → COMPLETED`，取消为 `CANCELED`。出勤合计取实际任务覆盖日期与打卡日期的并集，跨天任务和打卡同日只算一次，分别返回任务、打卡、迟到、早退、请假天数。日期范围含起点、不含终点，最长 366 天。

### 任务冲突、分页与出勤边界

任务、工作班和请假按北京时间 `[start, end)` 判定重叠。休息日覆盖当天 00:00 至次日 00:00。
待执行任务按计划时间检查，执行中任务从实际出发开始，检查范围覆盖计划结束和当前进度。
车队停运后，管理员创建任务和司机出发都会返回 409，已出发的任务仍可报到完成。
发生冲突时可读取响应的 `data.reason`，例如 `TASK_NOT_PENDING`、`TASK_OVERLAPS_REST`、
`REST_OVERLAPS_TASK`、`LEAVE_OVERLAPS_SHIFT`、`SHIFT_OVERLAPS_LEAVE` 和 `FLEET_STOPPED`。

派单支持可选的 `Idempotency-Key` 请求头。响应丢失时，管理员用相同标识和同一份请求重试，
返回原任务并只保留一次派单审计。相同标识搭配不同条件时返回 409，原因为 `TASK_REQUEST_KEY_REUSED`。
任务时间按毫秒保存。

原任务和请假列表保留数组响应。游标列表返回 `records`、`hasNext` 和 `nextCursor`，按 ID 倒序。
下一页把 `nextCursor` 传为 `beforeId`。筛选状态变化时从首页刷新。
出勤统计的 `end` 排他。打卡按班次日期、任务按实际报到区间归属，未出发即取消的任务不产生出勤。
同日打卡和任务只合计一次。获批请假覆盖的自然日单独计数，可以与同一天的出勤同时计入各自指标。

## 司机档案与车辆绑定

新增司机、变更所属车队时，目标车队须启用。已属于停用车队的司机可编辑一般档案。创建司机和保存档案分别使用 `POST /api/drivers` 和 `PUT /api/drivers/{id}`，保存档案时只按已持久化的车辆绑定返回结果。

分配或换绑调用 `PATCH /api/drivers/{id}/vehicle?vehicleId=11`，需要司机已签到、当前在岗，且涉及的司机及车辆没有执行中的运输任务。省略 `vehicleId` 可解绑，执行中任务结束后才能解绑。工号与已有登录账号或工号冲突时返回 HTTP 409，`data.reason=EMPLOYEE_NO_CONFLICT`、`data.field=employeeNo`。其余档案业务异常沿用响应正文中的 `code`。

## 保养维修和油耗

下表路径以 `/api/operations/fleet` 开头，限平台管理员。

| 方法和路径 | 用途 |
|---|---|
| `GET /vehicles/{id}/odometer`、`PUT /vehicles/{id}/odometer` | 查询、录入最新里程表读数 |
| `GET /vehicles/{id}/maintenance`、`POST /vehicles/{id}/maintenance` | 查询、新增周期保养计划 |
| `POST /maintenance/{id}/complete` | 登记本周期保养完成并计算下次保养 |
| `PATCH /maintenance/{id}/enabled?enabled=true` | 启停保养计划 |
| `GET /vehicles/{id}/repairs`、`POST /vehicles/{id}/repairs` | 查看、新建维修单 |
| `POST /repairs/{id}/transition` | 顺序流转维修单状态 |
| `POST /repairs/{id}/acceptance-rejection`、`GET /repairs/{id}/history` | 验收退回、查看流转历史 |
| `POST /vehicles/{id}/fuel`、`PUT /fuel/{id}` | 新增、修正加油记录 |
| `POST /fuel/{id}/void`、`GET /fuel/{id}/revisions` | 作废加油记录、查看修正前后值 |
| `GET /vehicles/{id}/fuel?start=2026-11-01&end=2026-12-01` | 分页查询油耗与同类车型基准 |

- 里程示例 `{"odometerKm":15500.5,"measuredAt":"2026-11-22T12:00:00"}`。以人工核对的里程表读数判断里程保养。里程和时间不可倒退。
- 保养计划示例 `{"name":"例行保养","intervalDays":90,"intervalKm":5000,"lastServiceDate":"2026-11-20","lastServiceOdometerKm":10000}`。按日期、按里程可分别使用，任一已到期即提示。如果同时设置两类周期，完成时提交日期和里程。完成示例 `{"cycle":1,"date":"2026-11-22","odometerKm":15500.5}`。客户端提交刚查询到的 `cycle`，重复提交旧周期返回 409。
- 新建维修单填写 `description`（维修项目或故障说明）。维修单依次使用 `REPORTED → ASSIGNED → IN_REPAIR → DONE → ACCEPTED → SETTLED`。派工时正文填写 `assignee`，维修厂填 `shop`，结算时填写 `cost`（免费填 0）。
- 同车只允许一张未结算维修单。报修时可填写同车的 `maintenancePlanId`。
  完工时可填 `completedOn` 和 `completedOdometerKm`。验收不通过提交 `reason`，
  工单回到 `IN_REPAIR`，旧实绩和退回记录仍可在历史中查看。
  验收通过后可用 `applyMaintenance: true` 和 `expectedCycle` 在结算事务中滚动关联保养计划。
  选择更新时，计划须已启用，且原周期与数据库一致。已停用计划暂停生成提醒。
- 加油示例 `{"fueledAt":"2026-11-22T11:00:00","liters":40.5,"unitPrice":7.25,"odometerKm":15400,"fillStatus":"FULL"}`。
  `fillStatus` 为 `FULL`、`PARTIAL` 或 `UNKNOWN`，旧数据迁移为 `UNKNOWN`。
  两笔已确认加满之间，累计上次加满之后到本次加满的油量，由本次记录的 `litersPer100Km` 展示正式油耗。
  `intervalStart`、`intervalEnd` 和 `intervalLiters` 给出区间时间和累计油量，`previousOdometerKm`
  和 `odometerKm` 给出两端读数。查询允许取开始日期之前的加满记录作起点。
- `calculationStatus` 区分 `VALID`、`NO_PREVIOUS`、`PARTIAL`、`UNKNOWN_FILL`、
  `UNKNOWN_IN_INTERVAL`、`ODOMETER_INVALID` 和 `VOIDED`。同车队同车型基准仅使用其他车辆
  的有效加满区间。有效区间不足时返回 `INSUFFICIENT_PEERS`，该车的正式油耗仍可查看。
  车型基准偏差和最少样本量仍使用 `operations.fuel.deviation` 与 `operations.fuel.min-peer-samples` 配置。
- 修正与作废均提交当前记录的 `version` 作为 `expectedVersion`，以及非空 `reason`。
  版本落后或记录已作废返回 409。查询时按最新有效记录重算。作废记录和变更历史保留，可在原时刻补录有效记录。

## 证照和期限提醒

- 管理员用 `PUT /api/operations/certificates` 登记或续期，正文如 `{"subjectType":"VEHICLE","subjectId":11,"certificateType":"INSURANCE","expiresOn":"2026-12-20","expectedVersion":0}`。首次登记用 `expectedVersion: 0`。续期提交上一次读取的 `version`，成功后返回新版本并沿用证照 ID。旧版本返回 409。车辆证照支持 `INSPECTION`、`INSURANCE`、`OPERATING_PERMIT`，司机证照支持 `DRIVER_LICENSE`、`DRIVER_QUALIFICATION`。
- 查询指定主体证照用 `GET /api/operations/certificates?subjectType=VEHICLE&subjectId=11`。管理员用 `GET /api/operations/reminders?fleetId=1&sourceType=CERTIFICATE` 补查提醒，`sourceType` 可省略或设为 `MAINTENANCE`。另可按 `sourceId`、`subjectType`、`subjectId`、`stage` 和 `createdFrom`、`createdTo` 筛选。生成时间用北京时间，结束时间排他。
- 默认每天北京时间 09:00 扫描。证照提前 30 天提醒，今天到期为 `DUE`，早于今天为 `EXPIRED`，即将到期为 `DUE_SOON`。旧提醒保留当时的阶段。保养提前 7 天或 500 km 提醒，到达目标日期或里程后进入到期阶段。相同到期日或保养周期的同阶段只生成一次，续期和保养完成后可产生新提醒。调试时可在本地配置 `operations.reminders.cron` 为 `*/10 * * * * *`，每 10 秒扫描一次。
- 新提醒提交后，在管理员的 `GET /api/alerts/stream` 连接收到 `event: operations-reminder`，包含提醒 ID、来源类型、主体和阶段。客户端用提醒列表读取完整日期或目标里程。所有运营写操作由统一操作日志记录。
