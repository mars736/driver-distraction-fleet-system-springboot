# 识别记录查询

识别查询支持筛选、分页、详情、已保存关键帧读取，以及关联告警和最新申诉摘要。管理员删除接口及权限边界见[报表和数据管理联调](report-and-data.md)。

## 权限与接口

全部请求使用 `Authorization: Bearer <token>`。管理员查看全部记录；司机仅查看识别记录中 driver_id 为本人的历史记录，不按车辆现在绑定的司机推算。无司机归属的记录只对管理员可见。

| 方法 | 地址 | 用途 |
| --- | --- | --- |
| GET | `/api/recognitions` | 筛选及分页 |
| GET | `/api/recognitions/{id}` | 单条详情 |
| GET / HEAD | `/api/recognitions/{id}/key-frame` | 读取已保存 JPEG / 校验后返回响应头 |

删除限管理员，且仅允许无告警或申诉引用的记录。不存在的记录和不属于当前司机的记录统一返回 404。无登录、失效 token 返回 401，未知角色返回 403，参数错误返回 400，不支持的响应格式返回 406。正文为现有 Result；错误明确使用 JSON，即使下载客户端的 Accept 只接受 image/jpeg。

## 筛选与分页

| 参数 | 说明 |
| --- | --- |
| fleetId / vehicleId / driverId / cameraId | 可选正整数，可组合 |
| behaviorCode | 小写 c0—c9；OOD 或非法预测的有效类别可为空 |
| ood | true / false，是否拒识 |
| participated | true / false，是否实际参与判定 |
| startTime / endTime | 不带时区的北京时间 ISO 日期时间，如 2026-11-16T10:00:00 |
| page | 从 1 开始，默认 1 |
| pageSize | 默认 10，范围 1—100 |

按采集时间 captured_at 筛选，区间为 `[startTime, endTime)`；同时给出两端时，起点必须早于终点。列表按采集时间倒序、ID 倒序稳定排序。超过末页时返回空数组和真实 total。

司机传入 driverId 只能进一步缩小本人范围，不能查询他人；ownerDriverId 不属于请求 DTO，前端不能自行指定服务端归属。

示例：

```http
GET /api/recognitions?vehicleId=12&ood=false&participated=true&startTime=2026-11-16T00:00:00&endTime=2026-11-17T00:00:00&page=1&pageSize=20
Authorization: Bearer <token>
```

分页返回为 `{ code, message, data: { list, total, page, pageSize } }`。

## 返回内容及前端衔接

每条记录包含 id、摄像头/车辆/司机/车队 ID、车牌及司机/车队显示名称、采集时间、原始 pred、有效 behaviorCode、maxP、energy、knnDist、OOD、participated、decisionReason、decisionReasonLabel、speedKmh、speedStatus、创建/更新时间和 keyFrameUrl。

### 识别判定原因

列表和详情的 `decisionReason` 保留原始代码。`decisionReasonLabel` 是供页面显示的中文文案。代码表示这一帧是否进入告警判定。参与判定的帧可能是正常驾驶，也可能触发告警。

| 代码 | 显示文案 |
| --- | --- |
| `EVALUATED` | 已参与告警判定 |
| `LOW_SPEED` | 低速静默 |
| `OOD` | 分布外样本 |
| `INVALID_PREDICTION` | 无效预测结果 |
| `OUT_OF_ORDER` | 采集时间乱序 |
| `MISSING_VEHICLE` | 未关联车辆 |
| `VEHICLE_NOT_FOUND` | 关联车辆不存在 |

旧记录中的未知代码会原样返回，中文标签为「未知判定原因」。`latestAppeals[].decisionReason` 继续返回人工处理理由。


ID 和识别结果保持采集时的历史值；车牌和姓名是当前档案的显示值，档案已不存在时名称为空，识别记录仍然可查。不返回密码、物理文件路径、内部 source_key 或进程内帧号。

- `linkedAlerts`：本帧关联的全部可见告警，包含 id、recordType、behaviorCode、status、smsStatus。速度未知异常也可出现在这里，不能据此把所有关联记录都当成司机违规。
- `latestAppeals`：每个相关目标最近一条可见申诉，包含 id、targetType、targetId、status、decisionReason、reviewedAt。既包括针对普通识别记录的申诉，也包括针对其关联告警的申诉。
- 对同一目标撤回后重新提交，只返回最新一次申诉摘要，完整历史仍通过申诉接口读取。一个识别帧有多个告警或多次申诉时，不会导致分页重复或 total 变大。

司机从这里取得目标 ID 后，继续使用 `POST /api/appeals`：已关联 SMS_ALERT/BACKLOG 时使用 targetType=ALERT 和告警 ID；无关联分心告警的有效普通分心帧使用 targetType=RECOGNITION 和识别 ID。正常帧、OOD、低速未参与帧及速度未知异常不满足申诉资格；最终资格及状态冲突仍以申诉接口校验为准。

申诉确认/撤销不改写原始 pred、behaviorCode 等元数据。普通识别记录的复核结果应查看 latestAppeals，关联告警的当前状态查看 linkedAlerts。

## 图片与一致性

关键帧只读取已有 `file.upload-dir/alerts/...` 文件。大多数普通帧没有保存图片，keyFrameUrl 为 null；元数据有路径但文件已丢失时，读取接口返回 404。前端获取图片需携带 Bearer token，可转 Blob 展示。

图片响应为 image/jpeg，设置 no-store；HEAD 同样检查归属和文件，正文为空。服务器校验规范化路径及真实路径，拒绝越出上传目录或读取申诉材料等其他目录；现有静态私有目录保护仍生效。

列表和详情使用 REPEATABLE_READ 只读事务。先给识别表分页，再批量读取本页告警和申诉，不逐条查询；记录、总数、告警状态和申诉摘要保持同一事务快照。

新增 RecognitionSecurityConfig 仅匹配 `/api/recognitions` 及子路径，复用现有 JWT、错误输出和跨域配置；新增异常处理器仅作用于本 Controller。既有档案、安全及异常处理源文件未改动。

## 数据库与验证

无需新增表、字段或迁移，使用申诉模块已有的十一张表。测试显式开启 `DRIVER_FLEET_DB_TESTS=true`，使用本机独立 driver_score_test 库的现有正式表结构，通过 SPRING_DATASOURCE_URL / USERNAME / PASSWORD 环境变量指定，不改写 application.yml。

```powershell
$env:DRIVER_FLEET_DB_TESTS = "true"
# 在当前测试进程设置指向专用库的 SPRING_DATASOURCE_*，不把密码写入文档或提交。
.\mvnw.cmd "-Dtest=RecognitionQueryIntegrationTest" test
```

集成测试使用真实 MySQL、MyBatis、JWT、申诉接口及临时 JPEG。覆盖组合筛选、分页排序与时间边界、无归属/他人记录、OOD 和未参与帧、申诉撤回后再提交、关键帧权限与路径、错误响应协商、旧路由兼容，以及查询期间另一个事务同时新增识别记录和提交复核的快照一致性。

本次在 Java 21 / MySQL 8.0.45 上使用独立测试库先执行 `clean test`，审阅修复后再次执行全量 `test`：173 项通过，0 失败、0 错误、0 跳过，其中新增识别查询测试 34 项。覆盖 Windows junction / 目录符号链接跨入申诉材料目录的回归。该数量基于不含评分模块的主分支，不与评分分支的 199 项混算。
