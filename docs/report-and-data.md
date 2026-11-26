# 异步报表和数据管理联调

## 数据库升级

在已完成第一批迁移的库上依次执行 `src/main/resources/db/migrations/` 下的三个脚本。

1. `20261122_report_jobs.sql`
2. `20261122_recognition_cleanup.sql`
3. `20261122_vehicle_track_index.sql`
4. `20261126_dashboard_report_notifications.sql`

新库执行完整 `schema.sql`。以下接口均需平台管理员 Bearer Token。查询时间未另说明时使用北京时间，范围含开始、不含结束。

## 异步 Excel 和 PDF

| 方法和路径 | 用途 |
|---|---|
| `POST /api/report-jobs` | 创建任务，HTTP 202 返回任务 ID 和 `PENDING` 状态 |
| `GET /api/report-jobs?page=1&pageSize=10` | 查询当前账号的导出历史 |
| `GET /api/report-jobs/{id}` | 查看状态、行数、失败原因和到期时间 |
| `GET /api/report-jobs/{id}/file` | 下载已生成文件，返回附件和对应的 MIME 类型 |

请求体包含 `type`、`format` 和以下筛选字段。格式为 `XLSX` 或 `PDF`。格式和筛选字段在创建时保存，数据按后台实际生成报表的时间读取。

| type | 筛选字段 |
|---|---|
| `ALERT` | `fleetId`, `vehicleId`, `driverId`, `behaviorCode`, `recordType`, `status`, `startTime`, `endTime`。沿用 CSV 筛选。 |
| `RECOGNITION` | `fleetId`, `vehicleId`, `driverId`, `cameraId`, `behaviorCode`, `ood`, `participated`, `startTime`, `endTime`。按原始识别数据导出。 |
| `DRIVER_SCORE` | 必填 `month`（如 `2026-11`），可选 `fleetId` 和 `driverId`。指定司机时导出该司机汇总，省略司机时导出排名。 |
| `FLEET_STATISTICS` | 必填 `startTime`、`endTime`，可选 `fleetId`。导出指定车队或全部车队的合计，含识别、各分心类别占比、告警及当前档案计数。 |

示例请求体。

```json
{
  "type": "ALERT",
  "format": "XLSX",
  "fleetId": 1,
  "startTime": "2026-11-20T00:00:00",
  "endTime": "2026-11-21T00:00:00"
}
```

- 前端先创建任务，再轮询详情。状态流转为 `PENDING → RUNNING → COMPLETED`，失败返回 `FAILED` 与原因。超过 10,000 条的明细报表提示缩小筛选范围。无匹配记录仍生成带表头的文件。
- 报表只允许申请人查看和下载。未完成时下载返回 409，过期返回 410，他人任务返回 404。状态为 `COMPLETED` 的文件保留 7 天，清理后历史仍可查询。创建、下载记入操作日志。
- 报表保存在 `file.upload-dir/reports`。多实例使用同一数据库时，应配置同一受保护的文件目录。生成中断的任务在 15 分钟后回收，可重试一次。部署时允许写入上传目录和 Java 临时目录。
- PDF 使用可嵌入的中文字体。Windows 默认找 `%WINDIR%/Fonts/simhei.ttf`，Linux 会尝试系统的 Noto Sans CJK。若缺少默认字体，在 Spring 配置 `report.pdf-font`（环境变量 `REPORT_PDF_FONT`）填字体绝对路径，TTC 需附字体下标，例如 `/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,2`。

### 防止重复创建和核对取数时间

前端发起一次新申请时生成一个请求标识，例如 UUID，并通过 `Idempotency-Key` 请求头提交。
响应丢失时沿用该标识及原申请条件重试。相同管理员使用相同标识和筛选条件会收到原任务，
包括已完成或已过期的任务。使用同一标识修改报表类型、格式或筛选条件时返回 HTTP 409，
`data.reason` 为 `IDEMPOTENCY_KEY_REUSED`。重新生成报表时使用新的标识。
旧客户端可以省略该请求头，继续沿用原申请方式。

`dataAsOf` 是后台开始读取本次报表数据时记录的北京时间。已生成的任务详情和文件
使用同一个值。Excel 在「取数信息」工作表展示，PDF 在标题下展示。历史任务缺少此信息时返回空。
报表文件按生成时的结果归档，之后的告警撤销影响在线结果和新生成的报表。

## 识别记录删除和关键帧清理

- 单条调用 `DELETE /api/recognitions/{id}`。批量调用 `POST /api/recognitions/batch-delete`，正文如 `{"ids":[101,102]}`，每批最多 100 条。返回 `deleted` 和 `cleanupQueued`。
- 已关联告警或曾有直接申诉的识别记录返回 409。批量中有冲突或找不到记录时整批回滚。删除操作按每条记录登记审计。已提交的告警和小时累计沿用原有判定结果。
- 后台定期清理确认已无引用的受管 JPEG，共用关键帧保留到最后一个引用移除时。删除失败会重试。默认每 5 秒扫描，使用 `recognition.cleanup.interval-ms` 调整频率。

## 轨迹回放和里程

`GET /api/vehicles/{id}/track?startTime=1700000000000&endTime=1700000060000&page=1&pageSize=500`

- `startTime` 和 `endTime` 是 Unix 毫秒，单次时间跨度最多 24 小时。每页 1 到 1000 点。超过 20,000 点时返回 422，请缩小时间范围。空轨迹返回 0 和空数组。
- 首页返回 `snapshotId`。查询后续页时传回该值，并沿用同一车辆、时间区间和分页大小。晚到样本会在下次新查询时纳入。点按 `sampledAt`、`driverId` 排序，包含司机、坐标、采样车速、`segment` 和 `distanceFromPreviousKm`。响应带全区间的 `totalPoints`、`totalDistanceKm`、`segmentCount`，里程包含跨页的有效相邻点。
- 司机变化、同一时间戳、间隔超过 30 秒或相邻点推算速度超过 200 km/h 时另外起段。地图按 `segment` 绘制，里程用同段相邻点的 Haversine 距离累加。坐标按存储原值返回。每点的 `coordinateSystem` 为 `GCJ-02` 或 `UNKNOWN`。全区间来源不同时顶层为 `MIXED`，并在来源变化处另起一段。空轨迹的顶层坐标系为 `null`。里程是 GPS 估算值。
