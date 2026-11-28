# 驾驶员分心识别车队系统后端

Spring Boot 3.5.3 / Java 21 / MyBatis / MySQL。当前提供认证、车队、车辆、司机和摄像头管理，以及视频抽帧、FastAPI 推理调用和告警判定落库。

## 本机启动

1. 安装 JDK 21 和 MySQL 8，将 `JAVA_HOME` 指向 JDK 21。项目自带 Maven Wrapper，无需单独安装 Maven。IDEA 的项目 SDK、模块语言级别和 Maven 导入器均选择 Java 21。
2. 在 MySQL 中执行：

   ```sql
   CREATE DATABASE IF NOT EXISTS driver_fleet CHARACTER SET utf8mb4;
   USE driver_fleet;
   ```

   新库执行 `src/main/resources/db/schema.sql`，创建三十八张业务表。已有申诉模块的数据库按顺序执行以下迁移。更旧的数据库先执行告警复核及申诉迁移。应用启动时不会自动执行这些脚本。

   - `src/main/resources/db/migrations/20261119_vehicle_telemetry.sql`
   - `src/main/resources/db/migrations/20261120_alert_key_frame_timeline.sql`
   - `src/main/resources/db/migrations/20261120_dashboard_indexes.sql`
   - `src/main/resources/db/migrations/20261121_vehicle_offline_notice.sql`
   - `src/main/resources/db/migrations/20261122_operation_log.sql`
   - `src/main/resources/db/migrations/20261122_sms_delivery.sql`
   - `src/main/resources/db/migrations/20261122_report_jobs.sql`
   - `src/main/resources/db/migrations/20261122_recognition_cleanup.sql`
   - `src/main/resources/db/migrations/20261122_vehicle_track_index.sql`
   - `src/main/resources/db/migrations/20261122_fleet_operations.sql`
   - `src/main/resources/db/migrations/20261123_violation_dispatch.sql`
   - `src/main/resources/db/migrations/20261126_vehicle_live_monitoring.sql`
   - `src/main/resources/db/migrations/20261126_alert_appeal_workflow.sql`
   - `src/main/resources/db/migrations/20261126_review_work_order.sql`
   - `src/main/resources/db/migrations/20261126_dashboard_report_notifications.sql`
   - `src/main/resources/db/migrations/20261126_workforce_task_attendance.sql`
   - `src/main/resources/db/migrations/20261126_fleet_care_certificates_fuel.sql`
   - `src/main/resources/db/migrations/20261126_camera_config_and_audit_identity.sql`
   - `src/main/resources/db/migrations/20261126_backend_acceptance.sql`
   - `src/main/resources/db/migrations/20261128_transport_orders.sql`

   执行最后一条迁移前，旧库可用以下查询找出缺少关联档案的数据。若有结果，先核对并修复归属，再执行迁移。

   ```sql
   SELECT v.id, v.fleet_id FROM vehicle v LEFT JOIN fleet f ON f.id = v.fleet_id
     WHERE v.fleet_id IS NOT NULL AND f.id IS NULL;
   SELECT u.id, u.vehicle_id FROM sys_user u LEFT JOIN vehicle v ON v.id = u.vehicle_id
     WHERE u.vehicle_id IS NOT NULL AND v.id IS NULL;
   SELECT c.id, c.vehicle_id FROM camera c LEFT JOIN vehicle v ON v.id = c.vehicle_id
     WHERE c.vehicle_id IS NOT NULL AND v.id IS NULL;
   ```
3. 在 `src/main/resources/application.yml` 中配置 `spring.datasource` 的地址、用户名和密码。启动前必须完成建库、建表，因为应用初始化时会查询摄像头表。
4. 在项目根目录执行：

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   macOS / Linux 使用 `bash ./mvnw spring-boot:run`。默认端口为 8080。首次启动会创建默认车队和管理员 `admin / admin123`。

登录接口为 `POST /api/auth/login`，JSON 请求字段为 `account` 和 `password`。登录成功后，后续请求使用 `Authorization: Bearer <token>`。

## 视频与推理

- 抽帧需要安装 FFmpeg，并通过 `video.ffmpeg-path` 指定可执行文件或使用 PATH 中的 `ffmpeg`。
- 推理服务不在本仓库。默认调用 `http://127.0.0.1:8000/predict`，可通过 `infer.fastapi-url` 修改。
- 摄像头配置接口 `/api/cameras` 及其子路径的所有读写操作均要求 `PLATFORM_ADMIN`。
- 车辆监控与摄像头启用共同控制抽帧。批准来源、配置及采集状态见[摄像头配置与采集联调](docs/camera-monitoring.md)。操作日志和短信验收见[监控与告警联调](docs/monitoring-delivery.md)。
- 没有启用摄像头时，日志显示“0 路摄像头”属于正常情况。推理结果已接入异步告警判定，有效车速来自司机 App，短信默认走模拟通道，可配置阿里云真实通道。

司机用 `POST /api/vehicle-telemetry` 上报绑定车辆的定位和速度。后端在识别帧的采集时间查找该司机的近期样本，详情见 [定位上报与联调](docs/vehicle-telemetry.md)。

司机 App 可用 `GET /api/vehicles/{id}` 读取本人当前绑定的车辆，未绑定、他人车辆或车辆不存在均返回 HTTP 404。车辆列表、轨迹、实时状态与写操作仍由管理员使用。

告警规则、边界处理、配置、数据库集成测试及假推理服务使用方法见 [告警判定说明](docs/alert-decision.md)。

告警查询提供 `GET /api/alerts`、`GET /api/alerts/{id}` 和 `GET /api/alerts/{id}/key-frame`，支持组合筛选和分页。管理员可查询全部记录，司机仅可查询本人记录及图片。参数、返回字段和前端图片读取方式见 [告警查询说明](docs/alert-query.md)。两次 PR 的审阅及修复记录见 [PR 审阅记录](docs/pr-review.md)。

管理员可通过 `POST /api/alerts/{id}/reviews` 确认或撤销待处理告警；`GET /api/alerts/{id}/reviews` 查看处理历史。状态和历史同事务提交，重复或并发覆盖返回 409。数据库升级和接口示例见 [告警处理说明](docs/alert-review.md)。

识别记录查询提供 `GET /api/recognitions`、`GET /api/recognitions/{id}` 和鉴权关键帧读取，支持时间、车队、车辆、司机、摄像头、行为类别、OOD 和参与状态筛选，并返回关联告警及最新申诉摘要。管理员查看全部，司机仅查看本人；用法见 [识别查询说明](docs/recognition-query.md)。

司机可通过 `/api/appeals` 对本人告警或普通分心记录提交申诉、补充文字和材料、撤回待复核申诉；管理员可复核确认或撤销。申诉历史与材料均按归属鉴权，待复核时禁止原处理入口覆盖告警。接口和状态流转见 [申诉说明](docs/alert-appeal.md)。评分查询及告警撤销后的原月份重算已接入，见 [司机评分说明](docs/driver-score.md)。

未登录、无效或过期 token 返回 HTTP 401；已登录但权限不足返回 HTTP 403。两者均使用 `{ "code": ..., "message": "...", "data": null }` 格式。

告警写入数据库后可通过 SSE 通知在线管理员和对应司机。连接、权限及断线补查见 [实时告警订阅](docs/alert-live-stream.md)。
管理员可按告警列表的条件下载 CSV，参数和下载方式见 [告警报表导出](docs/alert-report-export.md)。异步 Excel/PDF、识别记录删除和轨迹回放见 [报表和数据管理联调](docs/report-and-data.md)。
管理员和司机工作台提供只读统计接口。数据口径、角色和接入路径见 [工作台基础统计](docs/dashboard-statistics.md)。
告警时间线提供 `GET /api/alerts/{id}/key-frames`，按顺序返回可用的前一帧、触发帧和后一帧。权限及前端接入方式见 [告警关键帧时间线](docs/alert-key-frame-timeline.md)。

车队运营提供排班打卡、请假审批、运输任务、维保油耗和证照提醒。接口与移库步骤见 [车队运营联调](docs/fleet-operations.md)。

管理员可先登记运输订单与两端地址，再从同车队候选司机中确认派发。司机使用现有任务接口报到，详情返回订单地址。参见 [运输订单与派单联调](docs/transport-orders.md)。

违规处置支持自动建单、手动与就近派发、工单关闭和人员建议审核。参见 [违规处置工单联调](docs/violation-dispatch.md)。

## 接口文档

- [离线 HTML 接口文档](docs/api/index.html)可直接在浏览器打开和搜索。
- [OpenAPI JSON](docs/api/openapi.json)可导入 Apifox 或 Swagger 工具。包含 Bearer JWT、SSE 票据、请求体、响应及二进制下载格式。

更新文档时，使用 JDK 21，将 Spring 数据源指向已导入完整 schema 的隔离 MySQL 库，再执行：

```powershell
$env:DRIVER_FLEET_OPENAPI_OUTPUT = Join-Path (Get-Location) 'docs/api'
.\mvnw.cmd '-Papi-docs' '-Dtest=OpenApiExportTest' test
Remove-Item Env:DRIVER_FLEET_OPENAPI_OUTPUT
```

## 验证

```powershell
# 权限和进程生命周期回归测试，不需要 MySQL、FFmpeg 或推理服务
.\mvnw.cmd "-Dtest=CameraSecurityTest,FfmpegManagerTest" test

# 全部常规测试：现有 contextLoads 测试需要配置可用的 MySQL 和上述业务表
.\mvnw.cmd test
```

`PipelineSmokeTest` 是手动运行的 `main`，需要 FFmpeg 和 FastAPI，不会随 Maven 单元测试自动执行。
