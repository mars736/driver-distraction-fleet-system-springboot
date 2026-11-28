# 告警视频证据联调

## 后端部署

1. 已有库执行 `src/main/resources/db/migrations/20261128_alert_video_evidence.sql`，新库使用 `schema.sql`。
2. 使用带 HLS 和 libx264 的 FFmpeg。把 FFmpeg 路径、批准的视频源、HDFS 账号和 DataNode 映射写到仓库外的本机配置。HDFS 管理员需创建 `/fleet-safety/evidence`，授予运行账号写权限。HDFS 地址配置沿用 [告警归档联调](alert-hdfs-archive.md)。
3. 配置一个具备磁盘空间的专用录像目录，目录位于静态文件映射之外。运行单个负责摄像头采集与录像任务的后端实例，重启时继续使用同一本机目录。切换 HDFS 地址或归档目录之前需迁移已归档视频。
4. 测试或演示按需启用录像。后台以有界 HLS 分段缓冲同一 FFmpeg 输入；旧分段和关闭的采集会话会清理。已截取但尚未写入 HDFS 的文件保存在本机暂存目录，集群恢复后自动重试。部署方应按项目申诉期确定 HDFS 证据保留规则。

```yaml
video:
  ffmpeg-path: C:/tools/ffmpeg/bin/ffmpeg.exe
  approved-sources:
    demo-car01: http://<SRS主机>:8080/live/car01.flv
  evidence:
    enabled: true
    workspace: C:/fleet-safety/private-video
    directory: /fleet-safety/evidence
```

在管理员摄像头配置中选择已批准源 `demo-car01`，开启车辆监控和摄像头，并选择约 1～2 秒的采样间隔。车辆需要有可识别的当前司机。开发联调可用视频流同学的单路 SRS 测试流。录像开关默认关闭。打开录像开关时，即使 JSONL 归档任务关闭，也需要完整 HDFS 连接配置。

## 录像和告警的关系

每条分心告警按 `alert_id` 关联一个视频任务，窗口为事件前 5 秒至后 10 秒。使用摄像头采集会话、事件时间和司机归属交集确定片段。换绑或断流时，只取同一会话内覆盖事件时刻的连续片段。实际时间范围未覆盖整个目标窗口时标为 `PARTIAL`，历史告警没有留存素材时显示 `UNAVAILABLE`。

HDFS 文件路径为 `/fleet-safety/evidence/date=YYYY-MM-DD/alert-<id>/clip.mp4`，日期按告警发生的北京时间划分。先上传临时文件，再发布到固定路径。HDFS 已写入、数据库尚未确认时，重试会核对同一文件的字节内容。视频去掉音轨，生成便于浏览器播放的 H.264 MP4，最大 16 MiB。管理员接口通过后端从 HDFS 读取。告警 JSONL 可通过其中的 `alertId` 和 `capturedAt` 关联视频。

## 管理端与司机 App

管理员查询 `GET /api/appeals/{id}` 时，告警类申诉的详情包含 `videoEvidence` 对象。

| 状态 | 管理端页面行为 |
| --- | --- |
| `PROCESSING` | 显示「视频归档中」，允许刷新详情 |
| `READY` | 展示实际起止时间及完整视频 |
| `PARTIAL` | 展示实际起止时间和「片段不完整」 |
| `UNAVAILABLE` | 提示暂无视频，继续使用关键帧和申诉材料 |

可播放对象包含 `actualStartAt`、`actualEndAt` 和 `playbackUrl`。实际起止时间按北京时间返回。用原 Bearer Token 发起 `GET /api/appeals/{id}/evidence-video`，以完整 MP4 创建 Blob URL 播放，离开页面时释放 Blob URL。接口成功返回 `video/mp4` 和 `Cache-Control: no-store`。404 表示申诉不存在或暂无可播放视频，503 表示归档暂时无法读取。司机 App 继续使用原申诉接口，视频仅开放给平台管理员。

## 验收

- 用已批准的测试流触发一条分心告警，等待目标窗口结束和后台归档。由该司机提交告警申诉，用管理员账号播放，核对实际时间范围。
- 启用采集后切换司机或短时断流，核对截短状态及关键帧。司机、匿名用户无法读取视频。
- 通过独立测试用例模拟 HDFS 写完后确认丢失。观察数据库中的重试次数以及 HDFS 同一告警下的唯一 `clip.mp4`。
