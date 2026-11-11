# 告警判定规则与联调说明

本阶段依据《后端架构》第八节及用户确认的补充规则实现判定、异步接收、落库和短信接口。沿用现有两种角色和全局唯一工号，不恢复架构文档中的旧账号模型。

## 判定规则

- 按车辆独立统计。同车多个摄像头的结果合并为一条有效帧序列，批次内按采集时间排序；晚于新帧才到达的旧时间帧保留元数据，但不参与判定。
- C1/C3 可混合组成连续 5 个有效帧。第 5 帧产生 `SMS_ALERT`，同一持续手机片段只产生一次；正常帧或其他分心行为结束手机连续序列。以帧数判断，不另设“必须持续 10 秒”的条件。
- 其他分心类别为 C2、C4—C9。同一连续分心片段中切换类别只算一次；中间出现手机行为也不重复累计其他分心，正常帧结束片段。
- 按北京时间自然小时，即每小时整点重置其他分心累计和本小时去重标记。第 30 次不触发，第 31 次产生本小时唯一一条 `BACKLOG`。跨整点仍在持续的片段不会凭空成为新事件；手机连续帧不因整点而清零。
- OOD 帧只跳过，不增加计数，也不清零有效帧序列。使用整数 `pred` 映射类别，不依赖 `class_name` 的文本。非法类别或非法数值不触发行为告警，并打断连续性。
- 已知速度 ≤ 5 km/h 时不累计、不产生违规或短信，并清除手机连续帧及当前分心片段；保留本小时已累计事件数。识别元数据仍保存，标记 `LOW_SPEED`、未参与判定。
- 未知、负数、非有限速度视为未知，继续判定。未知状态开始时产生一次 `SPEED_UNKNOWN` 异常，恢复已知后再次未知才重新记录；该类型不是司机分心违规，不应扣分或发短信。
- 司机切换、告警队列丢帧、判定未提交且处理重试耗尽后，会打断连续帧和当前片段，不将跨缺口的结果误认为连续行为。判定已提交后的短信派发失败不会打断连续性。
- 无车辆归属、车辆不存在的帧只记录跳过原因，不生成告警。

## 数据与执行顺序

重新执行 `src/main/resources/db/schema.sql`，在现有四张表之外增加：

| 表 | 用途 |
| --- | --- |
| `recognition_record` | 每帧识别元数据、参与标记、跳过原因、车速状态、关键帧路径 |
| `alert_record` | 手机提醒、后台分心记录、速度未知异常及短信状态 |
| `alert_vehicle_state` | 每辆车的累计与去重状态，用于重启恢复 |

判定逻辑位于 `service.alert.rule`，不依赖 Spring、数据库或系统时钟。`AlertResultListener` 通过 `InferWorker.addListener` 注册，回调只复制结果并放入有界队列，不在推理线程里执行数据库、文件或短信操作。

同车处理通过状态行锁串行化。识别记录、告警和状态在同一事务中更新；UUID `source_key` 用于处理重试去重，进程内 `frameId` 仅供排查。违规触发时保存当前 JPEG，数据库回滚时清理对应文件。事务提交后才处理短信，条件更新防止重复领取。

队列满时拒绝新帧，输出 ERROR 并累计 `rejectedFrames`；处理异常限次重试，耗尽且判定未提交时累计 `failedFrames`，已提交后的短信派发失败则单独累计 `deliveryFailures`，均输出 ERROR。短信失败不重置已提交的片段状态。这些结果不会伪装为正常识别。队列不是持久化消息队列，异常退出或关闭期限耗尽时，尚未处理的帧可能丢失；已提交的计数和记录保留。

## 速度和短信占位

默认 `VehicleSpeedProvider` 返回未知，未伪造行驶速度。本阶段没有司机 App/GPS 上报接口或速度超时检测；后续实现此接口时必须按采集时间取速度，并处理上报过期。

默认 `SmsSender` 只输出模拟日志，结果为 `SIMULATED`，不会调用外部短信通道。没有有效司机、手机号或司机已停用时标记 `SKIPPED_NO_RECIPIENT`；明确绑定了另一辆车时标记 `SKIPPED_RECIPIENT_MISMATCH`；发送器异常标记 `FAILED`。只有真实发送器明确返回成功时才允许 `SENT`。

`PENDING` 表示等待处理，`SENDING` 表示已领取。真实短信接入还需实现以告警 ID 为键的通道幂等和投递对账，处理进程在发送期间退出留下的 `SENDING` 状态；本阶段没有后台短信补发器。

## 配置

以下默认值在 `AlertProperties` 中定义，可以通过 YAML 或环境变量覆盖，不需要修改源码：

```yaml
alert:
  sms-consecutive-frames: 5
  other-hourly-threshold: 30
  silent-speed-kmh: 5
  queue-capacity: 128
  max-attempts: 3
  retry-delay-ms: 1000
  shutdown-wait-ms: 5000
```

关键帧保存在 `file.upload-dir` 下的 `alerts/yyyy/MM/dd/`。只保存触发时当前帧，没有实现告警前后帧缓存、时间线查询、申诉、评分或 WebSocket 推送。告警列表、详情和鉴权图片读取见 [告警查询说明](alert-query.md)。

## 验证

```powershell
# 无需数据库、FFmpeg、模型或短信通道
.\mvnw.cmd "-Dtest=AlertRuleEngineTest,AlertResultListenerTest,SmsDispatcherTest" test

# 已配置 MySQL 且执行建表脚本后，开启真实数据库集成测试
$env:DRIVER_FLEET_DB_TESTS = "true"
.\mvnw.cmd clean test
Remove-Item Env:\DRIVER_FLEET_DB_TESTS
```

集成测试会创建并清理自己的车队、车辆和司机夹具，关键帧使用独立临时目录。其中一项通过本地假 HTTP 推理服务验证完整的“入队 → InferWorker → HTTP → 回调 → 判定 → MySQL → 模拟短信”链路；不使用真实模型。

没有推理服务时，可安装 Python 3 后运行：

```powershell
py -3 tools/mock_inference.py --pred 1
```

该服务只监听本机 8000 端口，所有上传帧都返回固定的 C1，不执行真实识别；`--pred 0` 可返回正常驾驶，`--ood` 可模拟拒识。Spring Boot 默认 `infer.fastapi-url` 已指向此端点。接真实视频源仍需要 FFmpeg。

可用以下 SQL 查看结果：

```sql
SELECT id, vehicle_id, driver_id, captured_at, record_type, behavior_code,
       event_count, sms_status, key_frame_path
FROM alert_record ORDER BY id DESC LIMIT 30;

SELECT id, vehicle_id, pred, ood, participated, decision_reason, speed_status
FROM recognition_record ORDER BY id DESC LIMIT 30;
```
