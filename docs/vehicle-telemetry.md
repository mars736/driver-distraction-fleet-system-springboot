# 车辆定位与车速上报

司机 App 上报已绑定车辆的 GPS 样本。告警服务按车、司机和识别帧采集时间查找样本，用于低速静默和速度未知判定。

## 数据库升级

已有数据库先执行 [车辆定位迁移](../src/main/resources/db/migrations/20261119_vehicle_telemetry.sql)，再启动新后端。新库执行完整 `db/schema.sql`。SQL 脚本须手动执行。

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

- 抽帧时确定当前有效司机。后端取该车、该司机在帧采集时刻之前或同一时刻的最近样本，按 `m/s × 3.6` 换算，最多使用 15 秒前的样本。
- 用 1.25 m/s 上报后输入一帧有效的 C1。用管理员查询 `GET /api/recognitions`，应看到 4.5 km/h、`KNOWN` 和 `LOW_SPEED`，该帧不产生行为告警。
- 停止上报，等采样时间过去 15 秒后再输入识别帧。应看到 `UNKNOWN`，从已知切到未知时应产生一条 `SPEED_UNKNOWN`。若最近样本的车速为 `null`，也按未知处理。
- 位置信息存入 `vehicle_telemetry`。轨迹回放和 GPS 估算里程见[报表和数据管理联调](report-and-data.md)。

## 管理端车辆实时状态

平台管理员可用 `GET /api/vehicles/live-status?fleetId=1&page=1&pageSize=10` 查询车辆定位状态。
省略 `fleetId` 时查询所有车队，单页最多 100 辆。响应沿用分页结构。

每项包含车辆档案标识、`driverId`、`online`、`locationStatus`、`sampledAt`、`receivedAt`、
`evaluatedAt`、`latitude`、`longitude`、`speedKmh`、`coordinateSystem` 及可空的 `movementCheck`。
司机账号无权访问此接口。

| locationStatus | 含义 |
| --- | --- |
| ONLINE | 当前唯一有效绑定司机的样本距服务端评估时间不超过 15 秒 |
| STALE | 样本超时，仍返回最后位置和时间，实时车速为空 |
| NO_SAMPLE | 有唯一有效绑定司机，尚无该司机的样本 |
| UNBOUND | 没有同车队且启用的绑定司机 |
| BINDING_CONFLICT | 有多名有效司机，暂停选择定位来源及生成提醒 |

时间均为 Unix 毫秒。旧样本的 `receivedAt` 可以为空。设备采样时间最多可领先接收时间 5 秒。
新样本的 `coordinateSystem` 为 `GCJ-02`，迁移前来源未取得确认的样本为 `UNKNOWN`。无定位时该字段为空。速度为 km/h，零表示有效停车速度，空值表示未知或已超时。
定位状态和视频采集是独立状态，采集接口见[摄像头配置与采集联调](camera-monitoring.md)。

## 管理端主动离线通知

已有库在启动新版前执行 `20261126_vehicle_live_monitoring.sql`。默认每 5 秒扫描启用监控的车辆。
仅当唯一有效司机和车辆正在执行同一项任务，且该司机出发后的最新样本超时时，才生成通知。
首次尚未上报时页面显示暂无定位。退出任务后仍可查询位置。
通知按任务、车辆、司机和最后样本去重。任务结束先提交时跳过通知。
管理员通过 `GET /api/alerts/stream` 接收 `vehicle-offline`，字段包括
`taskId`、`vehicleId`、`driverId`、`fleetId`、`sampledAt` 和 `detectedAt`。

实时通知沿用单实例 SSE。断线期间可重新查询 `/api/vehicles/live-status` 获取当前状态。
可通过 `telemetry.offline-monitor.enabled=false` 关闭扫描，或配置
`telemetry.offline-monitor.interval-ms` 调整扫描间隔。

## 停运车辆行驶核查

车队通过现有启停接口停用后开始一个新的停运周期。只有在本周期收到、且采样时间也属于本周期的新样本才参与判断。
同一辆车、同一有效司机连续两条递增样本均严格大于 5 km/h，且采样间隔不超过 15 秒时，保存一条核查记录。
重传和晚到旧样本不会推进计数。低速、速度为空、间隔超时、换绑或车队恢复运营都会断开连续记录。
同一段连续行驶只提醒一次。

平台管理员可通过 `stopped-fleet-motion` SSE 事件收到核查记录。实时状态的 `movementCheck` 返回当前仍有效的
核查结果，其中 `firstSampledAt` 和 `secondSampledAt` 是证据样本时间，同一个事件可用 `id` 去重。
恢复运营、换绑或当前位置超时后，不再显示旧核查状态，已保存的核查证据保留。

现有双绑数据会显示为绑定冲突。由管理员确认并解绑或停用多余的关联后恢复判定。
启用司机、新建或修改绑定共用车辆锁，避免再次产生双重有效绑定。
