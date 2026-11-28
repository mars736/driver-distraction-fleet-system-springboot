package regression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_OPENAPI_OUTPUT", matches = ".+")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class, properties = {
        "springdoc.paths-to-match=/api/**", "springdoc.api-docs.version=openapi_3_0",
        "report.worker.enabled=false", "telemetry.offline-monitor.enabled=false"
})
@AutoConfigureMockMvc
class OpenApiExportTest {
    private static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/login", "/api/auth/register",
            "/api/auth/refresh", "/api/auth/fleets");
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete", "head", "options");
    private static final String ERROR_SCHEMA = "#/components/schemas/BusinessError";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;

    @Test
    void exportCurrentControllerContract() throws Exception {
        var admin = jdbc.queryForMap("SELECT id,token_version FROM sys_user WHERE username='admin'");
        var authenticated = "Bearer " + jwt.generateToken(((Number) admin.get("id")).longValue(),
                ((Number) admin.get("token_version")).intValue());
        byte[] raw = mvc.perform(get("/v3/api-docs").header("Authorization", authenticated))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var spec = (ObjectNode) json.readTree(raw);
        enhance(spec);
        assertThat(spec.path("paths").size()).isGreaterThan(50);
        assertThat(spec.at("/components/schemas/ShiftRequest/properties/expectedVersion").isObject()).isTrue();
        assertThat(spec.at("/components/schemas/Task/properties/destinationAddress").isObject()).isTrue();
        var operations = spec.path("paths");
        String orderPath = "/api/operations/transport-orders/{id}";
        var assignment = operations.path(orderPath + "/assign").path("post");
        assertThat(assignment.path("parameters").toString()).contains("Idempotency-Key");
        var assignmentBody = resolveSchema(spec, assignment.at("/requestBody/content/application~1json/schema"));
        assertThat(assignmentBody.path("required").toString()).contains("driverId", "vehicleId", "expectedVersion");
        var orderResponse = resolveSchema(spec, operations.path(orderPath).path("get")
                .at("/responses/200/content/application~1json/schema"));
        assertThat(resolveSchema(spec, orderResponse.at("/properties/data")).at("/properties/assignments").isObject()).isTrue();
        var candidateResponse = resolveSchema(spec, operations.path(orderPath + "/candidates").path("get")
                .at("/responses/200/content/application~1json/schema"));
        var candidatePage = resolveSchema(spec, candidateResponse.at("/properties/data"));
        assertThat(resolveSchema(spec, candidatePage.at("/properties/list/items")).at("/properties/plateNo").isObject())
                .isTrue();
        assertThat(assignment.at("/responses/409/content/application~1json/examples/RATING_UNAVAILABLE/value/data/reason")
                .asText()).isEqualTo("RATING_UNAVAILABLE");
        var shifts = spec.path("paths").path("/api/operations/workforce/shifts").path("put");
        var shiftExamples = shifts.at("/requestBody/content/application~1json/examples");
        assertThat(shiftExamples.at("/create/value/expectedVersion").asLong(-1)).isZero();
        assertThat(shiftExamples.at("/update/value/expectedVersion").asLong(-1)).isEqualTo(1);
        assertThat(shifts.at("/responses/409/content/application~1json/examples/SHIFT_OVERLAPS_REST/value/data/reason")
                .asText()).isEqualTo("SHIFT_OVERLAPS_REST");
        assertThat(shifts.at("/responses/409/content/application~1json/examples/REST_OVERLAPS_SHIFT/value/data/reason")
                .asText()).isEqualTo("REST_OVERLAPS_SHIFT");
        assertThat(spec.path("paths").path("/api/drivers").path("post")
                .at("/responses/409/content/application~1json/examples/EMPLOYEE_NO_CONFLICT/value/data/field")
                .asText()).isEqualTo("employeeNo");
        assertThat(spec.at("/paths/~1api~1vehicles~1{id}~1track/get/parameters").toString()).contains("snapshotId");
        assertThat(spec.at("/paths/~1api~1alerts~1{id}~1key-frame/get/responses/200/content/image~1jpeg/schema/format")
                .asText()).isEqualTo("binary");

        Path output = Path.of(System.getenv("DRIVER_FLEET_OPENAPI_OUTPUT"));
        Files.createDirectories(output);
        String content = json.writerWithDefaultPrettyPrinter().writeValueAsString(spec).replace("\r\n", "\n") + "\n";
        Files.writeString(output.resolve("openapi.json"), content, StandardCharsets.UTF_8);
        String data = Base64.getEncoder().encodeToString(json.writeValueAsBytes(spec));
        Files.writeString(output.resolve("index.html"), HTML.replace("__OPENAPI_BASE64__", data), StandardCharsets.UTF_8);
    }

    private void enhance(ObjectNode spec) {
        spec.remove("servers");
        var info = spec.withObjectProperty("info");
        info.put("title", "驾驶员分心识别与车队管理 API");
        info.put("version", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        info.put("description", "从验收分支的 Spring MVC 路由和 DTO 导出。目标服务地址以部署配置为准。"
                + "部分档案接口会用 HTTP 200 返回非 200 的业务 code，请同时检查响应正文。");
        var components = spec.withObjectProperty("components");
        var schemes = components.withObjectProperty("securitySchemes");
        var bearer = schemes.putObject("bearerAuth");
        bearer.put("type", "http").put("scheme", "bearer").put("bearerFormat", "JWT");
        schemes.putObject("streamTicket").put("type", "apiKey").put("in", "query").put("name", "ticket");
        spec.putArray("security").addObject().putArray("bearerAuth");
        var schemas = components.withObjectProperty("schemas");
        var error = schemas.putObject("BusinessError").put("type", "object");
        var fields = error.putObject("properties");
        fields.putObject("code").put("type", "integer");
        fields.putObject("message").put("type", "string");
        fields.putObject("data").put("type", "object").put("nullable", true);
        coordinateSystems(schemas, "Point", false, "GCJ-02", "UNKNOWN");
        coordinateSystems(schemas, "VehicleLiveStatus", true, "GCJ-02", "UNKNOWN");
        coordinateSystems(schemas, "VehicleTrack", true, "GCJ-02", "UNKNOWN", "MIXED");
        nullableFields(schemas, "Task", "orderId", "orderNo", "originAddress", "destinationAddress");
        nullableFields(schemas, "OrderRow", "taskId", "driverId", "vehicleId");
        nullableFields(schemas, "TransportOrderDetail", "taskId", "driverId", "vehicleId");
        nullableFields(schemas, "TransportOrderCandidate", "vehicleId", "plateNo", "unavailableReason");
        ((ObjectNode) schemas.at("/TransportOrderCandidate/properties/unavailableReason")).withArrayProperty("enum").addNull();
        ((ObjectNode) schemas.path("SafetyRating")).put("nullable", true);

        var paths = spec.withObjectProperty("paths");
        paths.properties().forEach(path -> path.getValue().properties().forEach(method -> {
            if (!(method.getValue() instanceof ObjectNode operation) || !METHODS.contains(method.getKey())) { return; }
            var responses = operation.withObjectProperty("responses");
            responses.elements().forEachRemaining(response -> {
                if (response.path("content") instanceof ObjectNode content && content.has("*/*")) {
                    content.set("application/json", content.remove("*/*"));
                }
            });
            if (PUBLIC_PATHS.contains(path.getKey())) {
                operation.putArray("security");
            } else {
                errorResponse(responses, "401", "未登录或令牌失效");
                errorResponse(responses, "403", "权限不足");
            }
            errorResponse(responses, "default", "业务或请求错误，使用 code、message、data 响应");
        }));
        shiftContract((ObjectNode) paths.path("/api/operations/workforce/shifts").path("put"));
        transportOrderContract(paths);
        var employeeNoConflict = conflict("工号与已有登录名或工号重复",
                Map.of("EMPLOYEE_NO_CONFLICT", "工号已被用作现有账号的登录名或工号"), "employeeNo");
        ((ObjectNode) paths.path("/api/drivers").path("post").path("responses")).set("409", employeeNoConflict);
        ((ObjectNode) paths.path("/api/drivers/{id}").path("put").path("responses")).set("409", employeeNoConflict.deepCopy());
        var reportCreate = (ObjectNode) paths.path("/api/report-jobs").path("post").path("responses");
        var accepted = (ObjectNode) reportCreate.remove("200");
        reportCreate.set("202", accepted.put("description", "报表任务已受理"));
        responses(paths, "/api/report-jobs", "post", "409", "IDEMPOTENCY_KEY_REUSED");
        responses(paths, "/api/alerts/{id}/reviews", "post", "409", "PENDING_APPEAL / ALREADY_REVIEWED");
        responses(paths, "/api/appeals/{id}/decision", "post", "409", "APPEAL_WITHDRAWN / APPEAL_ALREADY_REVIEWED / ALERT_STATUS_CHANGED");
        responses(paths, "/api/operations/certificates", "put", "409", "证照已新增或版本已变更");
        responses(paths, "/api/operations/fleet/fuel/{id}", "put", "409", "加油记录版本已变更或已作废");
        responses(paths, "/api/vehicles/{id}/track", "get", "422", "轨迹点过多，请缩小范围");
        detail(paths, "/api/operations/workforce/shifts", "put",
                "首次创建 expectedVersion=0；更新传当前 version。同一司机按北京时间检查跨夜工作与休息。");
        detail(paths, "/api/drivers/{id}", "put",
                "资料更新忽略请求中的 vehicleId；绑车或解绑使用 PATCH /vehicle。");
        detail(paths, "/api/drivers/{id}/vehicle", "patch",
                "省略 vehicleId 表示解绑，分配车辆要求司机在岗。正在执行任务时返回业务 code=409。");
        detail(paths, "/api/vehicles/{id}/track", "get",
                "翻页传上一页 snapshotId，并沿用同一车辆及时间范围。UNKNOWN 为来源待核，MIXED 表示包含不同来源。");
        detail(paths, "/api/operations/certificates", "put",
                "首次登记 expectedVersion=0；续期传当前 version，证照 ID 保持一致。");
        detail(paths, "/api/operations/fleet/fuel/{id}", "put",
                "修正必须提交 fillStatus、expectedVersion 和非空 reason。");

        for (String path : Set.of("/api/recognitions/{id}/key-frame", "/api/alerts/{id}/key-frame",
                "/api/alerts/{id}/key-frames/{offset}", "/api/cameras/{id}/recent-frames/{frameId}")) {
            binary(paths, path, "image/jpeg");
        }
        binary(paths, "/api/appeals/{id}/materials/{materialId}", "image/jpeg", "image/png", "application/pdf");
        binary(paths, "/api/appeals/{id}/evidence-video", "video/mp4");
        responses(paths, "/api/appeals/{id}/evidence-video", "get", "404", "申诉不存在或没有可播放的视频");
        responses(paths, "/api/appeals/{id}/evidence-video", "get", "503", "HDFS 视频暂时无法读取");
        detail(paths, "/api/appeals/{id}/evidence-video", "get",
                "仅平台管理员可读。将完整 MP4 作为 Blob 播放，使用现有 Bearer JWT。");
        binary(paths, "/api/report-jobs/{id}/file", "application/pdf",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        binary(paths, "/api/alerts/export", "text/csv");
        var stream = (ObjectNode) paths.path("/api/alerts/stream").path("get");
        var security = stream.putArray("security");
        security.addObject().putArray("bearerAuth");
        security.addObject().putArray("streamTicket");
        binary(paths, "/api/alerts/stream", "text/event-stream");
        var streamHead = (ObjectNode) paths.path("/api/alerts/stream").path("head").path("responses");
        streamHead.remove("200");
        streamHead.putObject("405").put("description", "仅支持 GET 订阅").putObject("headers")
                .putObject("Allow").putObject("schema").put("type", "string").put("example", "GET");
        responses(paths, "/api/alerts/stream", "get", "429", "订阅连接超过服务端限制，请退避重试");
    }

    private static JsonNode resolveSchema(ObjectNode spec, JsonNode reference) {
        return spec.at(reference.path("$ref").asText().substring(1));
    }

    private static void nullableFields(ObjectNode schemas, String name, String... fields) {
        var properties = schemas.path(name).path("properties");
        for (String field : fields) {
            ((ObjectNode) properties.path(field)).put("nullable", true);
        }
    }

    private static void transportOrderContract(ObjectNode paths) {
        String root = "/api/operations/transport-orders";
        var create = (ObjectNode) paths.path(root).path("post").at("/requestBody/content/application~1json");
        create.putObject("example").put("fleetId", 1).put("taskType", "器械运输").put("description", "送货交接")
                .put("originAddress", "集美区示例路 10 号 A 库").put("destinationAddress", "湖里区示例路 20 号收货口")
                .put("startsAt", "2030-12-03T08:00:00").put("endsAt", "2030-12-03T12:00:00")
                .put("requirement", "NORMAL");

        var assign = (ObjectNode) paths.path(root + "/{id}/assign").path("post");
        ((ObjectNode) assign.at("/requestBody/content/application~1json"))
                .putObject("example").put("driverId", 18).put("vehicleId", 42).put("expectedVersion", 1);
        assign.withObjectProperty("responses").set("409", conflict("订单派发或候选资格冲突", Map.of(
                "ORDER_VERSION_CONFLICT", "订单已变化，请刷新后再操作",
                "ORDER_NOT_DISPATCHABLE", "订单当前不在待派单状态",
                "ORDER_REQUEST_KEY_REUSED", "请求标识已用于不同派发条件",
                "RATING_UNAVAILABLE", "高要求任务需要已发布的司机长期评级",
                "DRIVER_NOT_ELIGIBLE", "司机或车辆已有冲突任务"), null));

        var orderStateConflict = conflict("订单状态或版本已变化", Map.of(
                "ORDER_NOT_DISPATCHABLE", "订单当前不在待派单状态",
                "ORDER_VERSION_CONFLICT", "订单已变化，请刷新后再操作"), null);
        ((ObjectNode) paths.path(root + "/{id}").path("put").path("responses")).set("409", orderStateConflict);
        ((ObjectNode) paths.path(root + "/{id}/cancel").path("post").path("responses"))
                .set("409", orderStateConflict.deepCopy());
        detail(paths, root + "/{id}/assign", "post", "重试同一派发使用相同 Idempotency-Key 和请求内容。");
    }

    private static void shiftContract(ObjectNode operation) {
        var media = (ObjectNode) operation.path("requestBody").path("content").path("application/json");
        var examples = media.putObject("examples");
        shiftExample(examples, "create", "首次排班", 0, "2030-12-01T20:00:00", "2030-12-02T04:00:00");
        shiftExample(examples, "update", "编辑当前版本为 1 的班次", 1,
                "2030-12-01T20:00:00", "2030-12-02T00:00:00");
        var shiftConflict = conflict("排班与当前版本、日期或业务安排冲突，或班次已有打卡记录",
                Map.of(
                        "SHIFT_PAST_DATE", "历史排班不可修改或补录",
                        "SHIFT_VERSION_CONFLICT", "排班已被修改，请刷新",
                        "SHIFT_OVERLAPS_REST", "工作班次与休息日重叠",
                        "REST_OVERLAPS_SHIFT", "休息日与跨夜工作班次重叠",
                        "SHIFT_OVERLAPS_SHIFT", "班次与相邻日期的排班重叠",
                        "REST_OVERLAPS_TASK", "休息日与未完成的运输任务重叠",
                        "SHIFT_OVERLAPS_LEAVE", "工作班次与已批准请假重叠"), null);
        var conflictMedia = shiftConflict.withObjectProperty("content").withObjectProperty("application/json");
        ((ObjectNode) conflictMedia.at("/schema/properties/data")).put("nullable", true);
        conflictMedia.withObjectProperty("examples").putObject("attendanceExists")
                .put("summary", "班次已有打卡记录").putObject("value").put("code", 409)
                .put("message", "已有打卡记录的班次不能修改").putNull("data");
        operation.withObjectProperty("responses").set("409", shiftConflict);
    }

    private static void shiftExample(ObjectNode examples, String name, String summary, long version,
                                     String startsAt, String endsAt) {
        examples.putObject(name).put("summary", summary).putObject("value")
                .put("driverId", 1).put("date", startsAt.substring(0, 10)).put("type", "LATE")
                .put("startsAt", startsAt).put("endsAt", endsAt).put("expectedVersion", version);
    }

    private static ObjectNode conflict(String description, Map<String, String> reasons, String field) {
        var response = JsonNodeFactory.instance.objectNode();
        var media = response.put("description", description).putObject("content").putObject("application/json");
        var schema = media.putObject("schema").put("type", "object");
        schema.putArray("required").add("code").add("message").add("data");
        var properties = schema.putObject("properties");
        properties.putObject("code").put("type", "integer").putArray("enum").add(409);
        properties.putObject("message").put("type", "string");
        var data = properties.putObject("data").put("type", "object");
        var required = data.putArray("required").add("reason");
        var dataProperties = data.putObject("properties");
        var reasonValues = dataProperties.putObject("reason").put("type", "string").putArray("enum");
        if (field != null) {
            required.add("field");
            dataProperties.putObject("field").put("type", "string").putArray("enum").add(field);
        }

        var examples = media.putObject("examples");
        new TreeMap<>(reasons).forEach((reason, message) -> {
            reasonValues.add(reason);
            var details = examples.putObject(reason).put("summary", message).putObject("value")
                    .put("code", 409).put("message", message).putObject("data").put("reason", reason);
            if (field != null) { details.put("field", field); }
        });
        return response;
    }

    private static void coordinateSystems(ObjectNode schemas, String name, boolean nullable, String... values) {
        var property = (ObjectNode) schemas.path(name).path("properties").path("coordinateSystem");
        if (nullable) { property.put("nullable", true); }
        var allowed = property.putArray("enum");
        for (String value : values) { allowed.add(value); }
        if (nullable) { allowed.addNull(); }
    }

    private static void errorResponse(ObjectNode responses, String code, String description) {
        responses.putObject(code).put("description", description).putObject("content")
                .putObject("application/json").putObject("schema").put("$ref", ERROR_SCHEMA);
    }

    private static void responses(ObjectNode paths, String path, String verb, String code, String description) {
        errorResponse(((ObjectNode) paths.path(path).path(verb)).withObjectProperty("responses"), code, description);
    }

    private static void detail(ObjectNode paths, String path, String verb, String text) {
        ((ObjectNode) paths.path(path).path(verb)).put("description", text);
    }

    private static void binary(ObjectNode paths, String path, String... types) {
        var success = ((ObjectNode) paths.path(path).path("get")).withObjectProperty("responses")
                .withObjectProperty("200");
        if (!success.has("description")) { success.put("description", "成功"); }
        var content = success.putObject("content");
        for (String type : types) {
            var schema = content.putObject(type).putObject("schema").put("type", "string");
            if (!"text/event-stream".equals(type)) { schema.put("format", "binary"); }
        }
    }

    private static final String HTML = """
            <!doctype html><html lang="zh-CN"><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>车队管理 API 接口文档</title>
            <style>
            body{font:15px system-ui,sans-serif;margin:0;background:#f4f7fb;color:#172b45}
            main{max-width:1040px;margin:auto;padding:30px 18px}a{color:#074db0}
            header{border-bottom:2px solid #16315a;margin-bottom:24px}h1{margin:0 0 8px}
            input{width:min(36rem,90%);padding:10px;margin:12px 0;border:1px solid #7795bc;border-radius:5px}
            details{background:#fff;border:1px solid #ccd8e6;border-radius:5px;margin:6px 0}
            summary{padding:10px;cursor:pointer;overflow-wrap:anywhere}strong{display:inline-block;color:#095285;margin-right:9px}
            article{padding:0 12px 12px}pre{background:#edf3fb;padding:12px;overflow:auto;margin:4px 0 14px}
            </style>
            <main><header><h1 id="title">车队管理 API</h1><p id="version"></p><p id="about"></p>
            <p><a href="openapi.json">下载 OpenAPI JSON</a></p></header>
            <label for="search">搜索方法、路径或 DTO</label><br><input id="search" type="search">
            <h2>接口</h2><div id="routes"></div><h2>数据结构</h2><div id="schemas"></div>
            <noscript>可下载 OpenAPI JSON 查看完整接口。</noscript></main>
            <script>
            const api=JSON.parse(new TextDecoder().decode(Uint8Array.from(atob('__OPENAPI_BASE64__'),c=>c.charCodeAt(0))));
            const title=document.getElementById('title'), search=document.getElementById('search');
            title.textContent=api.info.title;
            document.getElementById('version').textContent=api.info.version;
            document.getElementById('about').textContent=api.info.description;
            const add=(parent,tag,text)=>{const el=document.createElement(tag);el.textContent=text;parent.append(el);return el};
            function block(parent,label,data){
              if(!data)return;
              add(parent,'h3',label);
              add(parent,'pre',JSON.stringify(data,null,2));
            }
            const entries=[];
            for(const [path,methods] of Object.entries(api.paths).sort()) for(const [verb,operation] of Object.entries(methods)){
              if(!['get','post','put','patch','delete','head','options'].includes(verb))continue;
              const box=document.createElement('details'),title=add(box,'summary','');
              add(title,'strong',verb.toUpperCase());
              title.append(path);
              const panel=add(box,'article','');
              if(operation.description)add(panel,'p',operation.description);
              block(panel,'认证',operation.security??api.security);
              block(panel,'参数',operation.parameters);
              block(panel,'请求体',operation.requestBody);
              block(panel,'响应',operation.responses);
              document.getElementById('routes').append(box);
              entries.push([box,[verb,path,operation.operationId,operation.description].join(' ').toLowerCase()]);
            }
            for(const [name,schema] of Object.entries(api.components.schemas).sort()){
              const box=add(document.getElementById('schemas'),'details','');
              add(box,'summary',name);
              add(box,'pre',JSON.stringify(schema,null,2));
              entries.push([box,name.toLowerCase()]);
            }
            search.addEventListener('input',()=>{const term=search.value.trim().toLowerCase();
              for(const [node,text] of entries)node.hidden=!text.includes(term);
            });
            </script></html>
            """;
}
