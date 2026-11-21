# 管理员告警 CSV 报表

使用管理员 Bearer Token 请求 `GET /api/alerts/export`。可发送 `Accept: text/csv`，也可省略 Accept。只接受其他格式时返回 HTTP 406。

筛选与告警列表一致，支持 `fleetId`、`vehicleId`、`driverId`、`behaviorCode`、`recordType`、`status`、`startTime`、`endTime`。时间采用不带时区的北京时间 ISO 格式，起点包含、终点不包含。报表按采集时间和告警 ID 倒序导出全部匹配记录，列表的分页参数不影响导出。

- 成功返回 `text/csv;charset=UTF-8`，文件名 `alerts.csv`，含 UTF-8 BOM。内容包括告警 ID、采集时间、车队、车辆、司机、行为、置信度、处理状态、短信状态和车速。查询结果为空时仍下载带表头的文件。
- 名称里的逗号、引号和换行保留在单元格内。可能被当成公式的文本会加单引号前缀。报表不包含图片路径、联系方式或账号凭据。
- 单次最多 10,000 条，超出返回 HTTP 422 及统一 JSON 提示，缩小筛选范围后可重新下载。未登录返回 401，司机返回 403，非法筛选返回 400。
- 前端可用带令牌的请求下载，先检查状态码，再将成功响应保存为 `alerts.csv`。错误响应应按 JSON 处理。

示例请求：`GET /api/alerts/export?fleetId=1&status=CONFIRMED&startTime=2026-11-20T00:00:00&endTime=2026-11-21T00:00:00`。

使用已有业务表，无需迁移。本阶段交付同步 CSV，Excel、PDF、异步任务和导出历史另外安排。
