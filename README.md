# 驾驶员分心识别车队系统后端

Spring Boot 3.5.3 / Java 21 / MyBatis / MySQL。当前提供认证、车队、车辆、司机和摄像头管理，以及视频抽帧、FastAPI 推理调用和告警判定落库。

## 本机启动

1. 安装 JDK 21 和 MySQL 8，将 `JAVA_HOME` 指向 JDK 21。项目自带 Maven Wrapper，无需单独安装 Maven。IDEA 的项目 SDK、模块语言级别和 Maven 导入器均选择 Java 21。
2. 在 MySQL 中执行：

   ```sql
   CREATE DATABASE IF NOT EXISTS driver_fleet CHARACTER SET utf8mb4;
   USE driver_fleet;
   ```

   再执行 `src/main/resources/db/schema.sql`，创建基础四张表，以及识别记录、告警、车辆判定状态和人工处理历史四张表。已有数据库也需要重新执行该脚本来增加新表，脚本使用 `CREATE TABLE IF NOT EXISTS`，不会删除已有表。已有七张表时，可只执行 `src/main/resources/db/migrations/20261111_alert_review.sql`。建表脚本不会自动执行。
3. 在 `src/main/resources/application.yml` 中配置 `spring.datasource` 的地址、用户名和密码。启动前必须完成建库、建表，因为应用初始化时会查询摄像头表。
4. 在项目根目录执行：

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   macOS / Linux 使用 `./mvnw spring-boot:run`。默认端口为 8080。首次启动会创建默认车队和管理员 `admin / admin123`。

登录接口为 `POST /api/auth/login`，JSON 请求字段为 `account` 和 `password`。登录成功后，后续请求使用 `Authorization: Bearer <token>`。

## 视频与推理

- 抽帧需要安装 FFmpeg，并通过 `video.ffmpeg-path` 指定可执行文件或使用 PATH 中的 `ffmpeg`。
- 推理服务不在本仓库。默认调用 `http://127.0.0.1:8000/predict`，可通过 `infer.fastapi-url` 修改。
- 摄像头配置接口 `/api/cameras` 及其子路径的所有读写操作均要求 `PLATFORM_ADMIN`。
- 视频读流结束或异常时会清理对应进程。管理员可调用 `PATCH /api/cameras/{id}/enabled?enabled=true` 重新启动该路，也可以更新摄像头配置触发重启。目前没有自动重连或定时重试。
- 没有启用摄像头时，日志显示“0 路摄像头”属于正常情况。推理结果已接入异步告警判定，默认速度来源为“未知”，短信只模拟发送，不调用真实通道。

告警规则、边界处理、配置、数据库集成测试及假推理服务使用方法见 [告警判定说明](docs/alert-decision.md)。

告警查询提供 `GET /api/alerts`、`GET /api/alerts/{id}` 和 `GET /api/alerts/{id}/key-frame`，支持组合筛选和分页。管理员可查询全部记录，司机仅可查询本人记录及图片。参数、返回字段和前端图片读取方式见 [告警查询说明](docs/alert-query.md)。两次 PR 的审阅及修复记录见 [PR 审阅记录](docs/pr-review.md)。

管理员可通过 `POST /api/alerts/{id}/reviews` 确认或撤销待处理告警；`GET /api/alerts/{id}/reviews` 查看处理历史。状态和历史同事务提交，重复或并发覆盖返回 409。数据库升级和接口示例见 [告警处理说明](docs/alert-review.md)。

未登录、无效或过期 token 返回 HTTP 401；已登录但权限不足返回 HTTP 403。两者均使用 `{ "code": ..., "message": "...", "data": null }` 格式。

## 验证

```powershell
# 权限和进程生命周期回归测试，不需要 MySQL、FFmpeg 或推理服务
.\mvnw.cmd "-Dtest=CameraSecurityTest,FfmpegManagerTest" test

# 全部常规测试：现有 contextLoads 测试需要配置可用的 MySQL 和上述八张表
.\mvnw.cmd test
```

`PipelineSmokeTest` 是手动运行的 `main`，需要 FFmpeg 和 FastAPI，不会随 Maven 单元测试自动执行。
