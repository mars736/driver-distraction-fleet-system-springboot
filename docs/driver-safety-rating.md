# Hadoop 司机长期安全评级

## 计分范围

司机从注册起，到本批截止时刻，已完整进入 HDFS 的历史分心告警参与计算。按每条告警最新版本判断，只统计 CONFIRMED 的 SMS_ALERT 和 BACKLOG。撤销后在下一批消除扣分。待复核、速度未知和注册前的事件不扣分。

短信告警基础扣 8 分，后台告警基础扣 2 分。每条扣分乘以 0.5 的「距事发天数 ÷ 180」次方，100 分减去总扣分后最低为 0，四舍五入保留 1 位小数。按显示分数分级。S ≥ 95，A ≥ 85，B ≥ 70，C ≥ 60，其余为 D。零违规司机为 100.0 分。hasDrivingHistory 表示截止前有已完成的运输任务。

## 部署和运行

1. 正式数据库按顺序完成前几批迁移，再执行 src/main/resources/db/migrations/20261128_driver_safety_rating.sql。新库使用 schema.sql。
2. 在 node1 创建 HDFS 目录 /fleet-safety/ratings 并将其授予 Spring Boot 与 Hadoop 运行账号。教学集群沿用 Admin1。本模块单独使用 /fleet-safety/ratings/yarn-staging，原集群暂存目录的权限按原配置管理。
3. 将仓库的 tools/driver-safety-rating 目录复制到 node1。确保 hadoop、mapred、javac、jar 和 flock 已在 PATH 中。用当前 Hadoop 集群的 Java 8 编译作业，Spring Boot 继续使用 JDK 21。

~~~bash
cd ~/driver-safety-rating
bash build.sh
export HADOOP_USER_NAME=Admin1
nohup bash run.sh target/driver-safety-rating.jar /fleet-safety/ratings /fleet-safety/alert-events > rating-worker.log 2>&1 &
~~~

持续模式只需在一台边缘节点运行一个实例。命令末尾可加 --once，只处理这一轮已经准备好的待处理批次。正式部署让系统服务在虚拟机重启后拉起该进程。HDFS 文件清单、版本号与实际作业输出是评级输入和结果的可追溯依据。

4. 在后端本机配置启用告警归档与长期评级，HDFS 连接沿用 [告警归档联调](alert-hdfs-archive.md)。

~~~yaml
alert:
  archive:
    enabled: true
driver-safety-rating:
  enabled: true
  directory: /fleet-safety/ratings
~~~

应用默认每周一北京时间 02:00 后发起一批。停机错过时，在下次启动补最近一次周期，截止时间取该批实际创建时刻。管理员可手动重算。同一时刻只有一批，回填或归档仍有积压时，批次保留在 PENDING。就绪后把司机名单和归档文件清单冻结为同一批输入，再经 HDFS 交接给 node1。

YARN 第一步按告警 ID 选截止前最新版本，第二步按司机汇总并为零违规司机输出分数。后端校验作业编号、结果等级和司机集合，在同一 MySQL 事务发布新批。失败时页面保留上一批，作业编号可用于查看 YARN 日志。超过 6 小时仍未完成的任务失败，修复原因后可手动重算。

## 管理端接口

| 操作 | 接口 | 说明 |
| --- | --- | --- |
| 手动重算 | POST /api/operations/driver-safety-rating/batches | 可传空请求体或空对象，202 返回批次编号。运行中返回 409，未启用返回 503 |
| 查批次 | GET /api/operations/driver-safety-rating/batches/{id} | 返回 dataAsOf、publishedAt、stage1ApplicationId、stage2ApplicationId、ruleVersion 和错误说明 |
| 司机档案 | GET /api/drivers 和 GET /api/drivers/{id} | 已发布结果在 safetyRating 中，字段含 batchId、score、level、dataAsOf、publishedAt、hasDrivingHistory |

批次状态为 PENDING、RUNNING、PUBLISHED、FAILED。字段 safetyRating 缺失时页面显示「暂无评级」，hasDrivingHistory 为 false 时可提示「暂无驾驶记录」。现有月度评分继续由 /api/scores 提供。高要求订单的评级准入及确认规则见 [运输订单与派单联调](transport-orders.md)。

## 验收

- 确认 HDFS 归档已完成后，用管理员账号发起评级，查看两条 YARN 作业进入 SUCCEEDED，再刷新司机管理页。
- 六名合成司机可验证 100.0/S、92.0/A、76.0/B、66.0/C、52.0/D 和 96.0/S。样本可覆盖注册前提醒、已撤销告警、截止后裁决、重复版本和 180 天半衰期。
- 暂时断开节点作业提交或注入损坏结果，验证之前发布的评级仍然可查。
