# 告警元数据 HDFS 归档联调

## 同步范围

- 归档 `SMS_ALERT` 与 `BACKLOG`。新告警、管理员直接复核引起的状态变更、申诉裁决引起的状态变更都会写入版本记录。
- 告警业务变更和归档队列在同一个 MySQL 事务中提交。归档 worker 独立批量写入 HDFS，失败批次会重试。MySQL 仍是查询和复核的数据源。
- 第一次启用时，worker 分批扫描迁移前的告警。历史告警补写当前状态，版本为 `0`。由新业务事务写入的版本从 `1` 开始，同一告警的最新状态按最高版本读取。
- 归档包含告警元数据。视频证据、每周评级和订单使用评级属于独立交付。本批无前端、App 接口变更。

## 部署

1. 已有 MySQL 库执行 `src/main/resources/db/migrations/20261128_alert_hdfs_archive.sql`。新库使用 `src/main/resources/db/schema.sql`。迁移需先于应用升级，启用 HDFS 前也需完成迁移。
2. 在集群启动 HDFS。将 `/fleet-safety/alert-events` 的写权限授予应用所用的 HDFS 用户。以下示例用于 VMware 教学集群的 Simple 模式。Windows 需要能够访问 NameNode WebHDFS 端口和各 DataNode HTTP 端口。
3. 在仓库外新建本机配置文件，例如 `C:/Users/Admin1/fleet-safety/application-local.yml`，填写以下配置。在 IDEA 的 Spring Boot 运行配置中设置环境变量 `SPRING_PROFILES_ACTIVE=local` 和 `SPRING_CONFIG_ADDITIONAL_LOCATION=file:C:/Users/Admin1/fleet-safety/`。如果已有本机配置目录，把以下内容合入现有配置。

```yaml
alert:
  archive:
    enabled: true
    webhdfs-uri: http://192.168.88.161:9870
    user: Admin1
    directory: /fleet-safety/alert-events
    datanode-port: 9864
    batch-size: 250
    interval: 1m
    datanodes:
      node1: 192.168.88.161
      node2: 192.168.88.162
      node3: 192.168.88.163
```

默认关闭上传。应用仍会积累待同步事件，开启后按批处理。批次先写临时文件，再重命名为固定路径。若 HDFS 已成功写入而 MySQL 尚未确认，重试会核对同一文件的内容后补记成功。

## 归档格式

文件为 UTF-8 JSON Lines，路径为 `/fleet-safety/alert-events/date=YYYY-MM-DD/batch-<UUID>.jsonl`。分区日期是批次归档日期，事件发生时间使用行内的 `capturedAt`。两个时间字段均为北京时间。

```json
{"alertId":1,"revision":2,"recognitionId":1001,"driverId":952,"vehicleId":951,"fleetId":1,"cameraId":7,"capturedAt":"2026-11-28T16:35:00.000","recordType":"BACKLOG","behaviorCode":null,"status":"CONFIRMED","source":"DIRECT_REVIEW","recordedAt":"2026-11-28T16:36:00.000"}
```

业务键为 `(alertId, revision)`，`source` 为 `CREATED`、`DIRECT_REVIEW`、`APPEAL` 或 `BACKFILL`。未知归属、摄像头及行为编码字段可为 `null`。消费者直接读取日期分区内的 `batch-*.jsonl`，隐藏临时文件和验收目录均在该范围之外。

## 验收

- 创建或复核一条测试告警，等待一个调度周期。查询批次同步时间、尝试次数及最近一次错误。集群断开期间可继续操作告警，集群恢复后观察已有批次重试。

```sql
SELECT id, partition_date, attempts, synced_at, last_error
FROM alert_archive_batch
ORDER BY partition_date DESC, next_attempt_at DESC
LIMIT 10;
```

- 在集群用 `hdfs dfs -ls /fleet-safety/alert-events` 查看日期分区，再用 `hdfs dfs -cat` 读取对应批次文件，核对告警编号、版本和状态。
