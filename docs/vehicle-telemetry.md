# 车辆定位与车速上报

司机 App 上报已绑定车辆的 GPS 样本。告警服务按车、司机和识别帧采集时间查找样本，用于低速静默和速度未知判定。

## 数据库升级

已有数据库先执行 [车辆定位迁移](../src/main/resources/db/migrations/20261119_vehicle_telemetry.sql)，再启动新后端。新库执行完整 `db/schema.sql`，共 12 张表。SQL 脚本须手动执行。

## 司机 App 接口

`POST /api/vehicle-telemetry` 使用 `Content-Type: application/json` 和司机的 `Authorization: Bearer <token>`。

| 字段 | 类型 | 说明 |
|---|---|---|
| `vehicleId` | 正整数 | 从 `GET /api/auth/me` 的 `data.vehicleId` 取值。服务端验证该车与当前司机、车队一致。 |
| `sampledAt` | 正整数 | Unix 毫秒，取 GPS 样本产生时间。重试沿用这个时间戳。 |
| `latitude` | 数值 | 纬度，范围 −90 到 90。 |
| `longitude` | 数值 | 经度，范围 −180 到 180。联调暂用 GCJ-02。 |
| `speedMps` | 数值或 `null` | SDK 原始单位 m/s，范围 0 到 100。有效停车速度传 0，无有效速度时传 `null` 或省略。 |

```json
{
  "vehicleId": 42,
  "sampledAt": 1795051200000,
  "latitude": 24.48,
  "longitude": 118.08,
  "speedMps": 1.25
}
```

联调时将示例的车辆 ID 和时间换成实际值。建议每 5 秒上报一次。服务端接受过去 5 分钟以内、最多比服务器时间快 5 秒的样本。相同车辆、司机和采样时间重复上报时保留首次数据，仍返回 200。`400` 表示参数或时间非法，`401` 表示未登录或凭证无效，`403` 表示角色无权限，`409` 表示没有绑定该车或车辆与车队不一致。响应正文使用 `{ "code": ..., "message": ..., "data": null }`。

## 判定和联调

- 摄像头的车辆和司机配置须与 App 上报一致。后端取该车、该司机在帧采集时刻之前或同一时刻的最近样本，按 `m/s × 3.6` 换算，最多使用 15 秒前的样本。
- 用 1.25 m/s 上报后输入一帧有效的 C1。用管理员查询 `GET /api/recognitions`，应看到 4.5 km/h、`KNOWN` 和 `LOW_SPEED`，该帧不产生行为告警。
- 停止上报，等采样时间过去 15 秒后再输入识别帧。应看到 `UNKNOWN`，从已知切到未知时应产生一条 `SPEED_UNKNOWN`。若最近样本的车速为 `null`，也按未知处理。
- 位置信息存入 `vehicle_telemetry`。地图轨迹查询、里程和围栏属于后续功能。

## 管理端车辆实时状态

平台管理员可用 `GET /api/vehicles/live-status?fleetId=1&page=1&pageSize=10` 查询车辆定位状态。
省略 `fleetId` 时查询所有车队，单页最多 100 辆。响应沿用分页结构。

每项返回 `vehicleId`、`fleetId`、`plateNo`、`online`、`sampledAt`、
`latitude`、`longitude`、`speedKmh`。在线指当前有效绑定司机在最近 15 秒内有定位样本，
与摄像头是否在线无关。过期样本仍返回最后位置和采样时间，实时车速为空。
没有有效绑定司机的样本时，车辆显示离线，采样时间、位置和车速均为空。
司机账号无权访问此接口。

## 管理端主动离线通知

已有库在启动新版前执行 `20261121_vehicle_offline_notice.sql`。默认每 5 秒扫描启用监控的车辆。
有过定位上报且最新样本超过 15 秒时，会在去重记录提交后向平台管理员的
`GET /api/alerts/stream` 连接发送 `event: vehicle-offline`。
事件包含 `vehicleId`、`fleetId`、`sampledAt` 和 `detectedAt`，时间为 Unix 毫秒。
车从未上报时只在查询接口显示离线。新样本恢复上报后再次超时，可触发新的通知。

实时通知沿用单实例 SSE。断线期间可重新查询 `/api/vehicles/live-status` 获取当前状态。
可通过 `telemetry.offline-monitor.enabled=false` 关闭扫描，或配置
`telemetry.offline-monitor.interval-ms` 调整扫描间隔。
