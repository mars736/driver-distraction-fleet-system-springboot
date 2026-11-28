# 给 Kimi 的 Dify 车队管理助手对接规划

请在本地 Dify 中搭一个管理员 Chatflow，包含截图或文字建单、自动生成排班方案、司机违规统计三条分支。Spring Boot 已提供下文的接口。Kimi 的交付物是可导入的 Dify DSL、模型节点配置说明和联调记录。

## 1. 连接和权限

- 在 Dify 应用内创建普通环境变量 `FLEET_API_BASE` 和 Secret 环境变量 `FLEET_AGENT_KEY`。Base 包含 `/api/agent/v1`，末尾省略斜杠。Windows Docker Desktop 的容器访问宿主机上的 Spring Boot 时，可先测试 `http://host.docker.internal:8080/api/agent/v1`。同一 Compose 网络则用后端服务名和容器端口。
- 所有 HTTP 节点固定添加 `Authorization: Bearer {{#env.FLEET_AGENT_KEY#}}`。POST 添加 `Content-Type: application/json`。节点 URL 固定，模型只生成请求字段。导出的 DSL 保留 Secret 变量引用，密钥在本机导入后配置。
- 后端把密钥绑定到一名现有、启用的平台管理员，复用业务权限和审计。密钥只在如下六个接口生效。对话应用限管理员使用，后台审计的操作者记录为配置的管理员。
- 所有请求先检查 HTTP 状态，再检查响应体 `code`。成功响应格式为 `{"code":200,"message":"操作成功","data":...}`。在 HTTP 节点后用 Code 节点解析 `body`，使用 `data` 字段取结果。
- 先在 Dify 的 HTTP 节点试跑 `GET /fleets`。收到 200 时再配置三条分支。区分 401 密钥错误、403 后端绑定管理员不可用、404 接口尚未启用或草稿已清理、409 草稿过期或业务冲突、422 统计范围过大、503 HDFS 或数据库暂时不可用。

| 方法与路径 | 用途 |
|---|---|
| `GET /fleets?offset=0&limit=50` | 取启用车队。返回 `data.items[]` 的 `id,fleetNo,name` 和 `data.hasMore` |
| `GET /drivers?fleetId=5&offset=0&limit=50` | 取启用司机。可省略 `fleetId`。返回 `data.items[]` 的 `id,fleetId,employeeNo,name` 和 `data.hasMore` |
| `POST /orders/preview` | 规范化订单并保存待确认草稿 |
| `POST /shifts/preview` | 计算排班、列出缺口并保存待确认草稿 |
| `POST /confirm` | 用草稿 ID 确认创建订单或保存排班 |
| `GET /violations` | 读取司机违规统计和一页明细 |

目录接口从 `offset=0` 开始，`limit` 最大 100。若当前页找不到用户指定的名称且 `hasMore=true`，继续翻页。名称相同时用车队编号或工号让用户选择。存在历史但已停用的司机，可使用其已知司机 ID 直接查询违规历史。 [Docker 容器访问宿主机](https://docs.docker.com/desktop/features/networking/networking-how-tos/#connect-a-container-to-a-service-on-the-host)

## 2. Chatflow 结构

1. 在 User Input 开启图片上传。将输入文字与图片交给支持视觉的 LLM。输入可能由甲方转发，把截图里的文字作为待提取数据。
2. 用意图分类节点或结构化输出区分 `ORDER`、`SHIFTS`、`VIOLATIONS` 和 `CLARIFY`。上传图片但意图不明确时先问用户。
3. 提取字段后检查缺项。中文相对日期以北京时间解释。实现时由 Code 节点取得北京时间并作为显式输入，时段缺失或有歧义时请用户补充。
4. 建单、排班采用固定节点顺序 `预览请求 → 展示预览 → Human Input → 确认分支 → POST /confirm`。Human Input 配置「确认」和「取消」两个动作。超时或取消分支直接结束。当前本地版本缺少 Human Input 时，可在会话变量保存 `draftId` 与摘要，在用户下一条消息明确确认时走固定分支，取消和过期时清空。
5. `draftId` 从预览节点的响应流向确认节点。确认接口使用固定 HTTP 节点。修改预览字段时重新生成草稿。确认成功后清理会话里的待确认引用。
6. 违规统计分支直接调用只读接口，把结果转成 ECharts JSON 和 Markdown 表格，再交给 Answer 节点。 [Dify LLM 节点](https://docs.dify.ai/en/cloud/use-dify/nodes/llm) · [Dify Human Input 节点](https://docs.dify.ai/en/cloud/use-dify/nodes/human-input)

## 3. 截图或文字建单

从甲方内容提取 `fleetName` 或 `fleetNo`、任务类型、描述、起运地址、送达地址、计划开始和结束时间、订单要求。通过车队目录解析出 `fleetId`。要求字段可省略，后端默认 `NORMAL`。仅在甲方或管理员明确提出高要求时填写 `HIGH`。

`POST /orders/preview` 示例。日期请按联调时实际的未来日期替换。

```json
{
  "fleetId": 5,
  "taskType": "货运",
  "description": "运送仓库备件",
  "originAddress": "示例市东区一号仓",
  "destinationAddress": "示例市西区二号仓",
  "startsAt": "2026-12-10T09:00:00",
  "endsAt": "2026-12-10T12:00:00",
  "requirement": "NORMAL"
}
```

前七个字段必填。地址各最多 255 字，任务类型最多 40 字，描述最多 500 字。时间用北京时间的本地日期时间，结束时间晚于开始时间。预览响应包含 `data.draftId`、`data.expiresAt` 和标准化的 `data.preview`。确认时发送如下请求。

```json
{
  "draftId": "此处引用预览节点返回的草稿 UUID"
}
```

`data.action` 为 `ORDER`，`data.result` 含 `orderId,orderNo,status,version`。给用户返回订单号和「待派单」。重复确认同一草稿会返回原回执。草稿确认有效期 30 分钟。

## 4. 自动生成排班并确认

提取车队、起止日期、每天早班和晚班所需人数。第一版使用早班 08:00–16:00、晚班 16:00–次日 00:00，日期范围左闭右开，最多 14 天，每种班次每天可以指定 0 至 50 人。已有班次按其类型计入需求。后端保留已有排班，避开已批准请假和已有班次冲突，优先安排只能上某一班的司机，再按本周期工作班次数轮换。

`POST /shifts/preview` 示例。

```json
{
  "fleetId": 5,
  "startDate": "2026-12-07",
  "endDate": "2026-12-14",
  "earlyPerDay": 2,
  "latePerDay": 1
}
```

- `data.preview.existingSlots` 是已覆盖的需求人次。`proposed[]` 含 `driverId,driverName,date,type,startsAt,endsAt`。`shortages[]` 含 `date,type,missing`。
- 按日期展示方案与缺口，有缺口时让管理员确认是否只保存可安排的班次。若 `data.draftId` 为空，直接展示结果并结束，无班次需要保存。
- 确认仍用 `POST /confirm`。成功时 `data.action=SHIFTS`，`data.result` 含 `fleetId,shiftIds`。后端在同一个事务内保存全部新增班次，任一班次校验失败返回冲突。遇到 409，重新生成方案，再请管理员确认。
- 如要每周定时预排，在独立的 Dify Workflow 配置北京时间触发器，调用相同预览接口。Chatflow 仍可按用户要求即时预排。 [Dify 定时触发器](https://docs.dify.ai/en/cloud/use-dify/nodes/trigger/schedule-trigger)

## 5. 司机违规分析和可视化

`GET /violations` 参数均可省略。两个日期参数使用 `YYYY-MM-DD`，`startDate` 含当天，`endDate` 不含当天。默认截止到明天零点并向前推一年。可加 `fleetId`、`driverId` 限定范围。明细 `page` 从 1 开始，`pageSize` 默认 20、最大 100，排行数量 `top` 默认 10、最大 50。

```text
GET /violations?startDate=2025-12-01&endDate=2026-12-01&fleetId=5&top=5&page=1&pageSize=20
```

| 响应字段 | Dify 展示 |
|---|---|
| `data.byMonth[]`，每项 `label,count` | 月度折线图。零记录月份由接口补 0 |
| `data.byBehavior[]`，每项 `label,count` | 违规类型横向柱状图。`UNCLASSIFIED` 显示「未分类」 |
| `data.topDrivers[]`，每项 `driverId,driverName,count` | 司机确认违规次数排行。姓名为空时显示司机 ID |
| `data.records[]` | 明细表。字段为 `alertId,driverId,driverName,fleetId,vehicleId,capturedAt,behaviorCode,recordType` |
| `data.startDate,endDate,total,page,pageSize` | 统计口径、合计和明细分页 |
| `data.coverage` | 展示 `latestBatchSyncedAt`。若 `backfillComplete=false` 或 `pendingEvents>0`，同时显示归档进度 |

统计按告警发生时间过滤。后端从已成功归档的 HDFS JSONL 读取事件，按告警 ID 取最新版本，然后统计有司机归属且最终为 `CONFIRMED` 的 `SMS_ALERT`、`BACKLOG`。日期范围最多 366 天，数据量超过单次限制时提示用户缩短区间。若 HDFS 文件读取失败，整次查询返回 503，聊天里提示稍后重试。结果为 0 且历史回填未完成时，显示「当前已归档范围内为 0，历史数据仍在回填」。

通过 Code 或 Template 节点生成图表配置，数字直接引用接口返回。ECharts 使用严格 JSON，代码块语言必须是 `echarts`。同一条回复可以拼接多个代码块和一个明细表。下面是月度图可用于预览的示例格式。

```echarts
{
  "tooltip": {"trigger": "axis"},
  "xAxis": {"type": "category", "data": ["2026-01", "2026-02"]},
  "yAxis": {"type": "value", "minInterval": 1},
  "series": [{"type": "line", "data": [3, 0]}]
}
```

按用户要求生成一张或多张图。只有用户明确要求明细时才输出较长列表。查询下一页时沿用原日期、车队和司机过滤条件。 [Dify Answer 节点](https://docs.dify.ai/en/cloud/use-dify/nodes/answer) · [ECharts 聊天渲染代码](https://github.com/langgenius/dify/blob/main/web/app/components/base/markdown-blocks/code-block.tsx#L260-L344)

## 6. 后端部署交接

已有库通过部署端执行 `src/main/resources/db/migrations/20261128_dify_agent.sql`。新库的 `schema.sql` 已包含该表。部署端为 Spring Boot 配置 `DIFY_AGENT_ENABLED=true`、`DIFY_AGENT_ACTOR_ID`（启用的平台管理员 ID）和 `DIFY_AGENT_API_KEY`（32–128 字符，由随机字节生成的独立密钥）。Dify 的 Secret 使用同一密钥。HDFS 统计沿用项目已有 WebHDFS 地址、账号和 DataNode 映射。

如果 Docker 与 Spring Boot 跨宿主机运行，部署端先确认容器能访问 Spring Boot 的监听地址。将 Dify 留在本机或管理员内网。甲方截图需全程留在本机时，配置本机视觉模型。

## 7. 验收用例

1. **连通与权限**。合法 Agent Key 可读取车队。缺密钥或错误密钥返回 401。相同密钥请求原管理端路径也返回 401。
2. **截图建单**。用一张包含两端地址和时间的测试截图完成预览。删去送达地址后应追问。取消时无订单。确认后返回待派单订单号。重复确认返回同一订单号。
3. **排班**。为测试车队生成一周早晚班。样本包含已批准请假、已有排班和人员不足。核对草稿保留原排班并列出缺口。预览后由另一位管理员修改拟排司机当天班次，确认应返回 409。
4. **统计**。准备跨两个月的归档，包含同一告警的确认与后续撤销、未分类告警、空白月份。核对图表与明细，撤销记录的次数应消除。检查明细翻页和 HDFS 不可用时的回复。
5. **交付**。导出可导入的 Chatflow DSL，附测试截图、模型要支持的输入类型、两个环境变量和逐项验收结果。
