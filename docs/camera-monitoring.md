# 摄像头配置与采集联调

摄像头接口仅平台管理员可用。车辆存在、车辆监控开启且摄像头启用时，后端才会启动已批准且配置有效的视频源。车辆关停或删除时停止对应采集并取消重连，车辆开关保留摄像头各自的配置状态。恢复车辆监控时只启动仍启用的摄像头。

## 配置批准的视频源

在后端部署配置中登记可使用的视频源。界面需要的来源标识可以从 `GET /api/cameras/sources` 获取。

```yaml
video:
  approved-sources:
    truck-a: "${FLEET_TRUCK_A_SOURCE}"
```

视频源地址由部署环境注入，支持 RTSP、HTTP 和 HTTPS。旧档案中的地址只有与登记来源完全一致时才可启动。编辑时可提交 `sourceId`，旧版客户端可提交与登记值完全一致的 `streamUrl`，后端将其保存为引用。部署环境应限制 FFmpeg 进程只能访问批准设备，视频源地址及凭据留在服务端。

## 接口行为

| 请求 | 行为 |
| --- | --- |
| `POST /api/cameras` | 默认停用。采样间隔采用秒，默认 2，允许 0.2 至 60。停用档案可以暂时不关联车辆或来源。 |
| `PUT /api/cameras/{id}` | 更新后返回落库数据。省略来源或间隔时保留原配置。启用中的摄像头修改车辆、采样间隔或来源返回 409。 |
| `PATCH /api/cameras/{id}/enabled?enabled=true` | 启用前校验有效车辆、批准来源和唯一绑定司机。车辆关闭监控时保留启用配置，采集处于阻断状态。重复启停可安全重试。 |
| `DELETE /api/cameras/{id}` | 可删除停用且无识别和告警历史的档案。有历史时可保留停用档案。 |
| `GET /api/vehicles/{id}/current-driver` | 返回 `state` 和 `driverId`。状态为 `BOUND`、`UNBOUND` 或 `AMBIGUOUS`。 |

摄像头详情和写操作遇到不存在的编号返回 HTTP 404。启停使用专用接口。列表、详情和保存回包返回 `sourceId`、`sourceKind`、`sourceConfigured`，前端编辑时可省略来源。

## 运行状态与归属

`enabled` 是档案配置开关。`captureState` 可取 `DISABLED`、`VEHICLE_BLOCKED`、`SOURCE_BLOCKED`、`STOPPED`、`CONNECTING`、`CAPTURING`、`RETRYING` 或 `FAILED`。`lastFrameAt` 和 `failureReason` 来自本进程的采集任务。重连可恢复 `FAILED` 状态，进程重启或停止采集会清除运行快照。

帧入队时读取该车唯一、同车队、启用的绑定司机。换绑后新帧携带新归属，历史记录保留采集时的司机。缺少唯一绑定时识别记录标记 `DRIVER_UNRESOLVED`，这一帧不触发司机告警、短信或扣分。

当前不同摄像头可同时采集。连续帧告警仍沿用已有车辆级规则，双摄像头共同参与告警的口径需联调确认。浏览器实时播放由媒体服务另行接入。
