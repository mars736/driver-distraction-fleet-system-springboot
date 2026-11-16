# 司机月度安全评分

本模块基于申诉 PR #5（`5e95fd0`）开发于 `feature/driver-score` 分支。先合并申诉模块，再审阅本分支的评分增量。

## 规则

沿用 `CommonConstant` 中的规则，每个自然月初始 100 分：

| 告警类型 | 每条有效扣分 |
| --- | --- |
| SMS_ALERT 短信告警 | 5 |
| BACKLOG 后台告警 | 1 |
| SPEED_UNKNOWN 速度异常 | 不扣分，不列入评分明细 |

等级：90—100 优（EXCELLENT），75—89 良（GOOD），60—74 一般（FAIR），0—59 差（POOR）。

按**告警采集时间**划分北京时间自然月，区间为“本月月初（含）至下月月初（不含）”。不按短信发送时间或管理员操作时间划分；短信模拟、失败、未发送均不改变违规告警的扣分。首次打开评分即可查询已有历史告警，无需回填。

本轮边界：

- OPEN、CONFIRMED 均扣分；提交申诉、补充材料、撤回申诉不暂停原扣分。
- REVOKED 不扣分。直接撤销告警、申诉成立撤销告警，都在原事务提交后的下一次查询中反映。
- 普通识别帧不是 BACKLOG 告警，不逐帧扣分，撤销普通帧也不会凭空加分。
- 展示评分 = `max(0, 100 - 有效累计扣分)`。累计扣分本身不截断：110 分扣分撤销一条 5 分后仍剩 105，展示分数仍为 0，而不是 5。
- 跨月撤销只影响告警所属的历史月，不能给当前月加分；下月按自己的告警独立从 100 分计算。
- 司机归属来自告警发生时的 driver_id，后来换车、调队不会把旧扣分转给其他司机。

## 实现方式

评分为现有告警的**只读统计**，没有独立余额表或定时重置任务。每条告警按主键只统计一次，不联结一对多的申诉事件或处理历史。这样重复访问、重复复核不会重复扣分或回补，复核事务回滚时分数也保持不变。

明细保留已撤销的告警：`originalDeduction` 是该类型原扣分，`effectiveDeduction` 是当前有效扣分（撤销后为 0）。它是原始记录的当前有效性视图，**不是不可变会计流水或已结算月报**；历史复核后允许重算。规则以后若发生变化或需要冻结月报，应另行设计带规则版本的账本/快照。

本模块不新建表，不修改业务数据、判定累计、原始识别或短信状态。现有 `idx_alert_driver_time(driver_id,captured_at)` 支撑按司机、月份查询，月度范围比较不对时间列调用函数。排名按司机联结月度告警统计后排序，数据量增大后再根据真实执行计划决定是否做月汇总表。

## 接口

均使用 Bearer JWT，返回现有 `Result` / `PageResult`。

| GET 地址 | 权限 | 返回 |
| --- | --- | --- |
| `/api/scores/me` | DRIVER | 本人月度分数、等级、有效分类计数、有效累计扣分、撤销计数 |
| `/api/scores/drivers/{driverId}` | 管理员或该司机本人 | 指定司机月度汇总 |
| `/api/scores/drivers/{driverId}/records` | 管理员或该司机本人 | 月度告警评分明细，含撤销记录 |
| `/api/scores/ranking` | PLATFORM_ADMIN | 司机月度排名 |

示例：`GET /api/scores/drivers/12?month=2026-11`。

- `month` 可选，严格 `yyyy-MM`，缺省为北京时间当前月；空字符串、非法月份、未来月份返回 400。支持 1000 年及之后的历史月份。
- 明细、排名支持 `page`（默认 1）、`pageSize`（默认 10，上限 100）；超出末页返回空数组和真实 total，使用 long 计算 offset。
- 排名可按 `fleetId` 筛选**当前归属**该车队的司机；所列司机的分数仍统计其整个月所有车队的历史告警。
- 排名包含无告警和已禁用的司机，不包含管理员；按分数从高到低，同分并列，采用 DENSE_RANK（例如 1、1、2）；同分以司机 ID 升序稳定分页，排名在分页之前计算。
- `fleetId` 仅用于排名筛选；汇总/明细由路径 driverId 确定范围。month/page/pageSize/fleetId 都执行参数校验。
- 他人或不存在的司机统一 404；管理员查询自己的 `/me` 为 403，应通过司机 ID 查询。
- 401/403/404/400/406 返回真实 HTTP 状态与 JSON Result；向查询地址 POST 返回框架的 405，没有改分、扣分或手动回补接口。
- 汇总一次 SQL 完成；明细/排名在 REPEATABLE_READ 只读事务中读取列表及总数，保持同一事务快照。

汇总字段：`driverId`、`name`、`employeeNo`、`fleetId`、`enabled`、`month`、`score`、`level`、`levelLabel`、`deductedPoints`、`smsAlertCount`、`backlogCount`、`revokedCount`。排名另带 `ranking`（其他接口为 null）。不返回密码、手机号或内部文件路径。

## 数据库与合并

评分不需要新增迁移脚本。运行前必须已有申诉模块的十一张表，按 README 完成前序迁移即可。本模块只读，不需要为旧告警重新发送事件或短信。

## 验证

Java 21，运行新增测试（默认独立 H2 MySQL 模式，不连接开发库）：

```bash
./mvnw -Dtest=ScorePeriodTest,DriverScoreIntegrationTest test
```

H2 测试从正式 schema.sql 生成临时副本，仅给索引加唯一后缀，适应两种数据库的索引命名范围差异。H2 仅为 test 依赖，不进入应用运行包。

真实 MySQL 8 测试应先在**本机专用** `driver_score_test` 库执行 schema.sql，然后设置：

```bash
export DRIVER_SCORE_MYSQL_TESTS=true
export DRIVER_SCORE_TEST_DB_URL='jdbc:mysql://127.0.0.1:3306/driver_score_test?serverTimezone=Asia/Shanghai'
export DRIVER_SCORE_TEST_DB_USER=root
# 在当前终端设置 DRIVER_SCORE_TEST_DB_PASSWORD，不把密码写入仓库。
./mvnw -Dtest=ScorePeriodTest,DriverScoreIntegrationTest test
```

测试在 MySQL 模式下只允许 localhost/127.0.0.1 的 `driver_score_test` 库，其他地址/库名直接拒绝。仅新增测试可用以上命令切换；现有数据库套件继续沿用 DRIVER_FLEET_DB_TESTS 和原数据源设置。

覆盖：月初/月末、闰月、默认北京月份、等级边界、0 分下限、跨月撤销、直接撤销、申诉通过/维持/撤回、重复查询/复核、事务提交可见性和回滚、历史归属、权限、分页、并列排名、无告警/禁用司机、参数与响应类型边界。

## 本轮实际结果（2026-11-16）

- Java 21 编译与常规回归通过；新增评分测试 60 项（17 项月份/等级边界，43 项 HTTP/MyBatis/JWT 集成用例），全部通过，无跳过。
- 包含新增测试的本机常规回归：118 项实际通过，0 失败、0 错误。另有 56 个原 MySQL 专用测试节点因环境条件跳过；跳过的参数化节点不等于全部展开后的执行次数。
- 本机未安装 MySQL，未声称通过真实 MySQL 回归。合并前需使用上述独立 MySQL 测试库命令补验评分 SQL；项目全量 MySQL 回归仍需在原约定测试环境运行。
- 未执行提交、推送、合并或服务器部署；既有申诉 PR #5 分支未改动。

## Windows 接续验证（PowerShell）

用户选择先推送评分分支，再在 Windows 上完成 MySQL 验证。当前 Mac 上真实 MySQL 尚未运行；此前“未提交/推送”是审查阶段的记录。

先保存 Windows 当前未提交修改（尤其本机 application.yml），再获取分支，不要用强制重置覆盖本机配置：

```powershell
git fetch origin
git switch feature/driver-score
# 如果本机尚无该分支：git switch --track origin/feature/driver-score
git pull --ff-only
```

使用 Java 21 / MySQL 8，先创建专用空测试库并导入正式表结构（在项目根目录运行，mysql 的 --password 会交互提示）：

```powershell
mysql --user=root --password --execute="CREATE DATABASE IF NOT EXISTS driver_score_test CHARACTER SET utf8mb4;"
mysql --user=root --password --database=driver_score_test --execute="source src/main/resources/db/schema.sql"
```

仅验证新增评分模块：

```powershell
$env:DRIVER_SCORE_MYSQL_TESTS = "true"
$env:DRIVER_SCORE_TEST_DB_URL = "jdbc:mysql://127.0.0.1:3306/driver_score_test?serverTimezone=Asia/Shanghai"
$env:DRIVER_SCORE_TEST_DB_USER = "root"
$secret = Read-Host "本机 MySQL 测试用户密码" -AsSecureString
$env:DRIVER_SCORE_TEST_DB_PASSWORD = [System.Net.NetworkCredential]::new("", $secret).Password
try {
    .\mvnw.cmd "-Dtest=ScorePeriodTest,DriverScoreIntegrationTest" test
} finally {
    Remove-Item Env:DRIVER_SCORE_TEST_DB_PASSWORD -ErrorAction SilentlyContinue
    Remove-Variable secret -ErrorAction SilentlyContinue
}
```

评分测试预期展开执行 60 项，0 失败、0 错误、0 跳过。请以本机真实结果为准。

若要同时验证原告警/申诉模块，重新设置上述密码变量，再增加下列环境变量（仍指向同一个专用测试库），运行不带 -Dtest 的全量测试：

```powershell
$env:DRIVER_FLEET_DB_TESTS = "true"
$env:SPRING_DATASOURCE_URL = $env:DRIVER_SCORE_TEST_DB_URL
$env:SPRING_DATASOURCE_USERNAME = $env:DRIVER_SCORE_TEST_DB_USER
$env:SPRING_DATASOURCE_PASSWORD = $env:DRIVER_SCORE_TEST_DB_PASSWORD
try {
    .\mvnw.cmd test
} finally {
    Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:DRIVER_SCORE_TEST_DB_PASSWORD -ErrorAction SilentlyContinue
}
```

完成后关闭该 PowerShell 窗口即可清除其余临时环境变量。不要把数据库密码或 JWT 发到聊天中；反馈 Maven 最后测试汇总即可。现有开发库不需要为评分模块新增表，也不应被用作本轮测试库。
