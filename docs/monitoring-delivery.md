# 后端监控与告警联调

## 数据库升级

最新主分支的已有库依次执行 `20261121_vehicle_offline_notice.sql`、`20261122_operation_log.sql`、`20261122_sms_delivery.sql`。脚本位于 `src/main/resources/db/migrations/`。新库执行完整 `schema.sql`。模拟短信也要建好短信任务表，以便识别只属于真实通道的任务。

## 车辆定位和摄像头

- 管理员查询 `GET /api/vehicles/live-status`，通过 `GET /api/alerts/stream` 接收 `vehicle-offline`。定位样本在 15 秒内有效，已上报的监控车辆超时后，在下一轮扫描发送通知。同一过期样本只通知一次。详见[车辆定位与车速上报](vehicle-telemetry.md)。
- FFmpeg 启动失败、退出或持续没有完整 JPEG 时自动重连。断帧超时取 `video.no-frame-timeout-ms`（默认 15000）和三倍抽帧间隔中的较大值。重试延迟从 `video.reconnect-initial-delay-ms`（默认 1000）逐步翻倍，上限 `video.reconnect-max-delay-ms`（默认 30000）。收到下一帧后恢复初始延迟。
- 摄像头停用、删除或应用关闭会停止重连，更新配置会重新建立连接。每次拉流使用新的会话标识，告警连续帧和关键帧时间线在会话切换时断开，已有小时累计依旧可查。
- 真机验收时断开视频源，等待日志出现重连记录，再恢复视频源并检查识别查询出现新帧。停车或在测试视频环境中操作。关闭摄像头后，再恢复视频源应保持停止。App 定位联调另用上报和停报验证车辆状态。

## 操作日志

管理员使用带 Bearer Token 的 `GET /api/operation-logs`。可选 `fleetId`、`actorId`、`action`、`targetId`、`startTime`、`endTime`。时间使用北京时间 ISO 格式，起点包含、终点不包含。默认第 1 页、每页 10 条，单页上限 100，按操作时间及 ID 倒序。

- 记录项包括 `actorId`、`fleetId`、`action`、`targetType`、`targetId`、`outcome`、`detailCode`、`requestId` 和 `operatedAt`。找不到账号时，认证失败记录的操作者和对象为空。
- 已接入司机注册及登录、账号修改密码及启停、车队/车辆/司机/摄像头档案写入、告警复核、申诉裁决和告警 CSV 生成。成功的写操作和日志同事务提交。日志详情仅写预定义状态码。报表日志代表后端已生成文件。
- 示例 `GET /api/operation-logs?fleetId=1&action=ALERT_REVIEW&page=1&pageSize=20`。

## 短信配置与验收

默认模式为 `simulated`。阿里云国内短信模式用部署环境注入以下变量。

| 变量 | 用途 |
|---|---|
| `FLEET_SMS_MODE=aliyun` | 启用真实通道 |
| `FLEET_SMS_ACCESS_KEY_ID`、`FLEET_SMS_ACCESS_KEY_SECRET` | RAM 用户凭据，权限包含 `dysms:SendSms` 和 `dysms:QuerySendDetails` |
| `FLEET_SMS_SIGN_NAME`、`FLEET_SMS_TEMPLATE_CODE` | 已审核通过的签名和模板 |
| `FLEET_SMS_OUT_ID_PREFIX` | 当前部署独用的业务编号前缀，1–32 位英文、数字、下划线或连字符 |
| `FLEET_SMS_BEHAVIOR_PARAMETER`、`FLEET_SMS_ALERT_ID_PARAMETER` | 可选，填写审核模板中对应的变量名，分别填充分心说明和告警 ID；纯文本模板可省略 |

- 真实任务与新告警在一个事务里创建。进程按告警 ID 领取一次，按预先保存的 `OutId` 对账。启用前的模拟短信及历史告警留在原来的发送流程里。领取前已撤销的告警标记为 `SKIPPED_REVOKED`。
- `SENT` 表示阿里云已受理，`DELIVERED` 表示回执确认送达，`DELIVERY_FAILED` 表示回执报告投递失败。`FAILED` 表示提交明确被拒，`SEND_UNKNOWN` 表示可能已受理，需要查单。超时或重启后只查单，查不到时继续观察，最多对账两天。两天后仍未确认时停止自动查询，可用阿里云控制台核对。回执终结或停止查询后，任务自动清空手机号，仅保留流水号、状态码和时间。
- 验收使用单独的测试库、绑定车辆的授权测试手机号和已审核模板。以司机身份上报大于 5 km/h 的车速，用测试视频触发连续 5 帧 C1/C3，在告警详情确认短信状态并核对手机收件。先在模拟环境验证规则，再启用真实通道。
- [阿里云发送接口](https://help.aliyun.com/zh/sms/developer-reference/api-dysmsapi-2017-05-25-sendsms)、[阿里云回执查询](https://help.aliyun.com/zh/sms/developer-reference/api-dysmsapi-2017-05-25-querysenddetails)。
