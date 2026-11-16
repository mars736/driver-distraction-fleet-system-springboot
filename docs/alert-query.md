# 告警查询接口

本阶段实现《后端架构》第九节中的多条件筛选、分页、详情和关联关键帧读取，沿用现有两种角色。确认、撤销和处理记录见 [告警处理说明](alert-review.md)；申诉、评分和前后帧时间线留待后续模块。

## 权限与响应

所有请求携带 `Authorization: Bearer <token>`。管理员可查看全部记录；司机只可查看告警记录中 `driver_id` 属于自己的记录，与车辆当前绑定关系无关。无司机归属的记录只对管理员可见。请求参数不能扩大司机权限。

列表和详情使用现有 `Result`；分页数据为 `{ list, total, page, pageSize }`。本模块参数错误返回 HTTP 400，未登录或登录失效返回 401，禁止的操作返回 403。不存在或不属于当前司机的记录统一返回 404，不泄露记录是否存在。错误正文均使用 `Result` 格式。其他既有业务接口的异常处理约定保持不变。

## 接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/alerts` | 筛选与分页 |
| GET | `/api/alerts/{id}` | 单条详情 |
| GET / HEAD | `/api/alerts/{id}/key-frame` | 鉴权后读取 JPEG / 读取响应头 |

以上查询路径不提供写操作，POST、PUT、PATCH、DELETE 均返回 403。管理员处理告警使用独立的 `POST /api/alerts/{id}/reviews`。

列表可选参数：

| 参数 | 含义与约束 |
| --- | --- |
| `fleetId` / `vehicleId` / `driverId` | 正整数，可组合筛选 |
| `behaviorCode` | 小写 `c0`—`c9` |
| `recordType` | `SMS_ALERT`、`BACKLOG`、`SPEED_UNKNOWN` |
| `status` | `OPEN`、`CONFIRMED`、`REVOKED` |
| `startTime` / `endTime` | 北京时间，不带时区的 ISO 日期时间，如 `2026-11-11T08:00:00` |
| `page` | 从 1 开始，默认 1 |
| `pageSize` | 1—100，默认 10 |

时间按采集时间 `captured_at` 筛选，范围为 `[startTime, endTime)`，同时传入时必须起点早于终点。列表按采集时间倒序，再按 ID 倒序；超出最后一页时列表为空，仍返回总数。

示例：

```http
GET /api/alerts?vehicleId=1&recordType=SMS_ALERT&startTime=2026-11-11T00:00:00&endTime=2026-11-12T00:00:00&page=1&pageSize=20
Authorization: Bearer <token>
```

返回记录包含时间、车辆/司机/车队 ID 和名称、行为类别、置信度、类型、计数、短信状态、处理状态、车速状态和关键帧接口地址。`SPEED_UNKNOWN` 的 `violation=false`；`SMS_ALERT` 和 `BACKLOG` 为 `true`。`smsSimulated=true` 仅对应 `SIMULATED`，不表示真实短信发送成功，`smsStatus` 保留原始状态。

## 关键帧

`keyFrameUrl` 为相对接口地址，如 `/api/alerts/123/key-frame`，文件不存在时接口返回 404。前端需携带 Bearer token 获取图片（可用 fetch 转 Blob 展示）；不要直接将地址放入无法携带该请求头的 img 标签。响应设置 `Cache-Control: no-store`，HEAD 同样执行权限与文件校验并返回空正文。

不返回服务器文件路径。直接访问 `/upload/alerts/**` 已关闭，静态解析器同时阻止 Windows 大小写变体和指向告警目录的别名。文件读取校验规范化路径及真实路径，拒绝越出上传目录的路径和符号链接目标。

## 验证

配置本机 MySQL 并执行现有七张表的建表脚本后：

```powershell
$env:DRIVER_FLEET_DB_TESTS = "true"
.\mvnw.cmd "-Dtest=AlertQueryIntegrationTest,AlertResultListenerTest" test
Remove-Item Env:\DRIVER_FLEET_DB_TESTS
```

查询测试使用真实 MySQL、MyBatis 和 JWT，创建并清理独立业务夹具及临时 JPEG，覆盖组合筛选、分页顺序、时间边界、参数校验、司机越权、匿名请求、关键帧权限、缓存策略、静态路径绕过和路径越界。无需真实摄像头、推理模型或短信通道。本阶段无需新增数据库表或字段。
