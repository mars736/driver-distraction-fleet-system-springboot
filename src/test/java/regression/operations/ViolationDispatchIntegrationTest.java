package regression.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import service.alert.live.AlertCreatedEvent;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class,
        properties = "telemetry.offline-monitor.enabled=false")
@AutoConfigureMockMvc
class ViolationDispatchIntegrationTest {
    private static final String API = "/api/operations/violation-dispatch";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate transactions;

    private long fleet;
    private long vehicle;
    private long otherVehicle;
    private long driver;
    private long admin;
    private long otherAdmin;
    private LocalDateTime capturedAt;
    private String key;

    @BeforeEach
    void fixtures() {
        capturedAt = LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
        key = "dispatch-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO fleet(fleet_no,name) VALUES (?,?)", key, "处置测试车队");
        fleet = jdbc.queryForObject("SELECT id FROM fleet WHERE fleet_no=?", Long.class, key);
        vehicle = vehicle("A");
        otherVehicle = vehicle("B");
        driver = user("driver", "DRIVER");
        admin = user("admin", "PLATFORM_ADMIN");
        otherAdmin = user("other", "PLATFORM_ADMIN");
        jdbc.update("UPDATE sys_user SET vehicle_id=? WHERE id=?", vehicle, driver);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("""
                DELETE n FROM work_order_legacy_revocation_note n
                JOIN violation_work_order w ON w.id=n.work_order_id
                JOIN alert_record a ON a.id=w.alert_id WHERE a.fleet_id=?
                """, fleet);
        jdbc.update("DELETE FROM alert_appeal_event WHERE appeal_id IN (SELECT id FROM alert_appeal WHERE fleet_id=?)", fleet);
        jdbc.update("DELETE FROM alert_appeal WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM alert_review_record WHERE alert_id IN (SELECT id FROM alert_record WHERE fleet_id=?)", fleet);
        jdbc.update("DELETE FROM alert_record WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM recognition_record WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM operation_log WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM dispatch_operator_location WHERE user_id IN (?, ?)", admin, otherAdmin);
        jdbc.update("DELETE FROM vehicle_telemetry WHERE vehicle_id IN (?, ?)", vehicle, otherVehicle);
        jdbc.update("DELETE FROM sys_user WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM vehicle WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM fleet WHERE id=?", fleet);
    }

    @Test
    void capturesOnlySameVehicleDriverAndEligibleViolationsAndCancelsOnReview() throws Exception {
        long when = capturedAt.atZone(ZONE).toInstant().toEpochMilli();
        jdbc.update("INSERT INTO vehicle_telemetry(vehicle_id,driver_id,sampled_at,latitude,longitude) VALUES(?,?,?,?,?)",
                vehicle, driver, when - 8_000, 24.48, 118.08);
        jdbc.update("INSERT INTO vehicle_telemetry(vehicle_id,driver_id,sampled_at,latitude,longitude) VALUES(?,?,?,?,?)",
                vehicle, driver, when + 1, 2.0, 2.0);
        jdbc.update("INSERT INTO vehicle_telemetry(vehicle_id,driver_id,sampled_at,latitude,longitude) VALUES(?,?,?,?,?)",
                otherVehicle, driver, when - 1, 9.0, 9.0);
        long alert = alert("SMS_ALERT", "c1", false);
        alert("SPEED_UNKNOWN", null, false);
        alert("BACKLOG", "c5", true);
        var order = call(HttpMethod.GET, API + "/orders?fleetId=" + fleet, admin, null, 200);
        assertThat(order).hasSize(1);
        var row = order.get(0);
        assertThat(row.path("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(row.path("latitude").asDouble()).isEqualTo(24.48);
        assertThat(row.path("positionSampledAt").asLong()).isEqualTo(when - 8_000);
        call(HttpMethod.GET, API + "/orders", driver, null, 403);
        review(alert, "REVOKED");
        assertThat(call(HttpMethod.GET, order(id(row)), admin, null, 200).path("status").asText()).isEqualTo("CANCELED");
        call(HttpMethod.POST, order(id(row)) + "/assign", admin, Map.of("assigneeId", admin), 409);
    }

    @Test
    void ordersWithoutPositionAllowManualAssignmentAndConcurrentAssignmentSucceedsOnce() throws Exception {
        long order = id(listFor(alert("BACKLOG", "c5", false)));
        call(HttpMethod.POST, order(order) + "/assign-nearest", admin, null, 409);
        call(HttpMethod.POST, order(order) + "/assign", admin, Map.of("assigneeId", driver), 400);
        call(HttpMethod.POST, order(order) + "/close", admin, Map.of("outcome", "HANDLED", "resolution", "完成"), 409);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> assignStatus(order, admin));
            var second = pool.submit(() -> assignStatus(order, otherAdmin));
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(call(HttpMethod.GET, order(order), admin, null, 200).path("status").asText()).isEqualTo("ASSIGNED");
    }

    @Test
    void ranksOperatorsByDistanceAndActiveLoad() throws Exception {
        long when = capturedAt.atZone(ZONE).toInstant().toEpochMilli();
        jdbc.update("INSERT INTO vehicle_telemetry(vehicle_id,driver_id,sampled_at,latitude,longitude) VALUES(?,?,?,?,?)",
                vehicle, driver, when, 24.48, 118.08);
        call(HttpMethod.PUT, API + "/operator-position", admin, Map.of("latitude", 24.48, "longitude", 118.08), 200);
        call(HttpMethod.PUT, API + "/operator-position", otherAdmin, Map.of("latitude", 24.485, "longitude", 118.08), 200);
        long first = id(listFor(alert("BACKLOG", "c2", false)));
        long second = id(listFor(alert("BACKLOG", "c4", false)));
        assertThat(call(HttpMethod.POST, order(first) + "/assign-nearest", admin, null, 200).path("assigneeId").asLong()).isEqualTo(admin);
        var candidates = call(HttpMethod.GET, order(second) + "/candidates", admin, null, 200);
        assertThat(candidates.get(0).path("userId").asLong()).isEqualTo(otherAdmin);
        assertThat(candidates.get(1).path("activeOrders").asLong()).isEqualTo(1);
        assertThat(call(HttpMethod.POST, order(second) + "/assign-nearest", admin, null, 200).path("assigneeId").asLong()).isEqualTo(otherAdmin);
    }

    @Test
    void closingDoesNotDoubleCountAndAppealRetainsTheHandledHistory() throws Exception {
        long source = alert("SMS_ALERT", "c1", false);
        review(source, "CONFIRMED");
        review(alert("BACKLOG", "c2", false), "CONFIRMED");
        review(alert("BACKLOG", "c4", false), "CONFIRMED");
        long workOrder = id(listFor(source));
        int before = score();
        call(HttpMethod.POST, order(workOrder) + "/assign", admin, Map.of("assigneeId", admin), 200);
        call(HttpMethod.POST, order(workOrder) + "/start", admin, null, 200);
        call(HttpMethod.POST, order(workOrder) + "/close", admin, Map.of("outcome", "HANDLED", "resolution", "已完成复核与沟通"), 200);
        assertThat(score()).isEqualTo(before);
        String suggestionPath = order(workOrder) + "/personnel-recommendation";
        var suggestion = call(HttpMethod.GET, suggestionPath, admin, null, 200);
        assertThat(suggestion.path("eligible").asBoolean()).isTrue();
        assertThat(suggestion.path("confirmedHighRiskCount").asInt()).isEqualTo(3);
        var decision = Map.of("action", "TRAINING", "note", "安排安全培训");
        call(HttpMethod.POST, suggestionPath, admin, decision, 200);
        call(HttpMethod.POST, suggestionPath, admin, decision, 409);
        long appeal = id(call(HttpMethod.POST, "/api/appeals", driver,
                Map.of("targetType", "ALERT", "targetId", source, "reason", "申请核对识别结果"), 200));
        call(HttpMethod.POST, "/api/appeals/" + appeal + "/decision", admin,
                Map.of("status", "REVOKED", "reason", "重新核对后撤销"), 200);
        var updated = call(HttpMethod.GET, order(workOrder), admin, null, 200);
        assertThat(updated.path("status").asText()).isEqualTo("CLOSED");
        assertThat(updated.path("alertStatus").asText()).isEqualTo("REVOKED");
        assertThat(updated.path("recommendationAction").asText()).isEqualTo("TRAINING");
        assertThat(call(HttpMethod.GET, suggestionPath, admin, null, 200).path("eligible").asBoolean()).isFalse();
        assertThat(score()).isGreaterThan(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revocationCancelsUnstartedOrdersAndRequiresWrapupForStartedOrders(boolean appeal) throws Exception {
        for (String state : List.of("PENDING", "ASSIGNED", "IN_PROGRESS", "CLOSED")) {
            long alert = alert("BACKLOG", "c5", false);
            long workOrder = id(listFor(alert));
            String path = order(workOrder);
            if (!state.equals("PENDING")) {
                call(HttpMethod.POST, path + "/assign", admin, Map.of("assigneeId", admin), 200);
            }
            if (state.equals("IN_PROGRESS") || state.equals("CLOSED")) {
                call(HttpMethod.POST, path + "/start", admin, null, 200);
            }
            if (state.equals("CLOSED")) {
                call(HttpMethod.POST, path + "/close", admin, Map.of("outcome", "HANDLED", "resolution", "原结论"), 200);
            }
            var before = call(HttpMethod.GET, path, admin, null, 200);
            if (appeal) {
                long appealId = id(call(HttpMethod.POST, "/api/appeals", driver,
                        Map.of("targetType", "ALERT", "targetId", alert, "reason", "请复查"), 200));
                call(HttpMethod.POST, "/api/appeals/" + appealId + "/decision", admin,
                        Map.of("status", "REVOKED", "reason", "复查撤销"), 200);
            } else {
                review(alert, "REVOKED");
            }

            var updated = call(HttpMethod.GET, path, admin, null, 200);
            assertThat(updated.path("alertStatus").asText()).isEqualTo("REVOKED");
            if (state.equals("CLOSED")) {
                assertThat(updated.path("outcome").asText()).isEqualTo("HANDLED");
                assertThat(updated.path("resolution").asText()).isEqualTo("原结论");
                assertThat(updated.path("closedAt")).isEqualTo(before.path("closedAt"));
                continue;
            }
            assertThat(updated.path("revocationSource").asText()).isEqualTo(appeal ? "APPEAL" : "DIRECT_REVIEW");
            assertThat(updated.path("revokedAt").isTextual()).isTrue();
            assertThat(updated.path("revocationReviewId").asLong()).isEqualTo(jdbc.queryForObject(
                    "SELECT id FROM alert_review_record WHERE alert_id=? AND to_status='REVOKED'", Long.class, alert));
            if (!state.equals("IN_PROGRESS")) {
                assertThat(updated.path("status").asText()).isEqualTo("CANCELED");
                call(HttpMethod.POST, path + "/start", admin, null, 409);
                continue;
            }

            assertThat(updated.path("status").asText()).isEqualTo("REVOKE_WRAPUP");
            call(HttpMethod.POST, path + "/assign", admin, Map.of("assigneeId", otherAdmin), 409);
            call(HttpMethod.POST, path + "/close", admin, Map.of("outcome", "HANDLED", "resolution", "结案"), 409);
            call(HttpMethod.POST, path + "/personnel-recommendation", admin,
                    Map.of("action", "TRAINING", "note", "安排培训"), 409);
            var notes = Map.of("workDone", "已完成沟通", "resolution", "按撤销结论停止处置");
            call(HttpMethod.POST, path + "/revocation-wrapup", driver, notes, 403);
            call(HttpMethod.POST, path + "/revocation-wrapup", admin, Map.of("workDone", " "), 400);
            var closed = call(HttpMethod.POST, path + "/revocation-wrapup", otherAdmin, notes, 200);
            assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
            assertThat(closed.path("outcome").asText()).isEqualTo("REVOKED_WRAPUP");
            assertThat(closed.path("wrapupWorkDone").asText()).isEqualTo("已完成沟通");
            assertThat(closed.path("closedBy").asLong()).isEqualTo(otherAdmin);
            assertThat(closed.path("assigneeId").asLong()).isEqualTo(admin);
            assertThat(closed.path("startedAt")).isEqualTo(before.path("startedAt"));
            call(HttpMethod.POST, path + "/revocation-wrapup", admin, notes, 409);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentRevocationSerializesWithStartOrClose(boolean closing) throws Exception {
        long alert = alert("BACKLOG", "c5", false);
        String path = order(id(listFor(alert)));
        call(HttpMethod.POST, path + "/assign", admin, Map.of("assigneeId", admin), 200);
        if (closing) { call(HttpMethod.POST, path + "/start", admin, null, 200); }
        var ready = new CountDownLatch(1);
        int result;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var revocation = pool.submit(() -> { ready.await(); review(alert, "REVOKED"); return true; });
            var operation = pool.submit(() -> {
                ready.await();
                var request = request(HttpMethod.POST, path + (closing ? "/close" : "/start"))
                        .header("Authorization", "Bearer " + jwt.generateToken(otherAdmin, 0));
                if (closing) {
                    request.contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(Map.of("outcome", "HANDLED", "resolution", "原结论")));
                }
                return mvc.perform(request).andReturn().getResponse().getStatus();
            });
            ready.countDown();
            result = operation.get(10, TimeUnit.SECONDS);
            assertThat(revocation.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(result).isIn(200, 409);
        var order = call(HttpMethod.GET, path, admin, null, 200);
        String expected = result == 200 ? (closing ? "CLOSED" : "REVOKE_WRAPUP")
                : (closing ? "REVOKE_WRAPUP" : "CANCELED");
        assertThat(order.path("status").asText()).isEqualTo(expected);
        if (expected.equals("REVOKE_WRAPUP")) {
            call(HttpMethod.POST, path + "/revocation-wrapup", admin,
                    Map.of("workDone", "已记录", "resolution", "撤销收尾"), 200);
        }
    }

    @Test
    void alertsLinkToOrdersAndCursorListingStaysStableWhenNewOrdersArrive() throws Exception {
        long oldestAlert = alert("BACKLOG", "c5", false);
        long middleAlert = alert("BACKLOG", "c5", false);
        long newestAlert = alert("BACKLOG", "c5", false);
        long noOrderAlert = alert("SPEED_UNKNOWN", null, false);
        long oldestOrder = id(listFor(oldestAlert));
        long newestOrder = id(listFor(newestAlert));

        String alertPath = "/api/alerts/" + oldestAlert;
        assertThat(call(HttpMethod.GET, alertPath, admin, null, 200).path("workOrderId").asLong()).isEqualTo(oldestOrder);
        assertThat(call(HttpMethod.GET, alertPath, driver, null, 200).path("workOrderId").isNull()).isTrue();
        assertThat(call(HttpMethod.GET, "/api/alerts/" + noOrderAlert, admin, null, 200).path("workOrderId").isNull()).isTrue();
        assertThat(call(HttpMethod.GET, order(oldestOrder), admin, null, 200).path("alertId").asLong()).isEqualTo(oldestAlert);

        String arrayPath = API + "/orders?alertId=" + oldestAlert;
        var exact = call(HttpMethod.GET, arrayPath, admin, null, 200);
        assertThat(exact.isArray()).isTrue();
        assertThat(exact).hasSize(1);
        assertThat(id(exact.get(0))).isEqualTo(oldestOrder);
        assertThat(call(HttpMethod.GET, arrayPath + "&fleetId=" + (fleet + 1), admin, null, 200)).isEmpty();

        String pagePath = API + "/orders/page?fleetId=" + fleet + "&pageSize=1";
        var page = call(HttpMethod.GET, pagePath, admin, null, 200);
        assertThat(page.path("records")).hasSize(1);
        assertThat(id(page.path("records").get(0))).isEqualTo(newestOrder);
        assertThat(page.path("hasNext").asBoolean()).isTrue();
        assertThat(page.path("nextCursor").asLong()).isEqualTo(newestOrder);

        alert("BACKLOG", "c5", false);
        var next = call(HttpMethod.GET, pagePath + "&beforeId=" + page.path("nextCursor").asLong(), admin, null, 200);
        assertThat(next.path("records").get(0).path("alertId").asLong()).isEqualTo(middleAlert);
        var last = call(HttpMethod.GET, pagePath + "&beforeId=" + next.path("nextCursor").asLong(), admin, null, 200);
        assertThat(id(last.path("records").get(0))).isEqualTo(oldestOrder);
        assertThat(last.path("hasNext").asBoolean()).isFalse();
        assertThat(last.path("nextCursor").isNull()).isTrue();

        review(oldestAlert, "REVOKED");
        assertThat(call(HttpMethod.GET, alertPath, admin, null, 200).path("workOrderId").asLong()).isEqualTo(oldestOrder);
        var revoked = call(HttpMethod.GET, API + "/orders/page?alertId=" + oldestAlert + "&status=CANCELED", admin, null, 200);
        assertThat(id(revoked.path("records").get(0))).isEqualTo(oldestOrder);
        call(HttpMethod.GET, pagePath, driver, null, 403);
        call(HttpMethod.GET, pagePath + "&beforeId=0", admin, null, 400);
        call(HttpMethod.GET, API + "/orders?alertId=0", admin, null, 400);
    }

    @Test
    void legacyRevocationCanBeRecordedOnceWithoutChangingTheOriginalOrder() throws Exception {
        long alert = alert("BACKLOG", "c5", false);
        long orderId = id(listFor(alert));
        String path = order(orderId);
        String notePath = path + "/legacy-revocation-note";
        var notes = Map.of("workDone", "已联系司机", "resolution", "结合原复核结论补记后续情况");
        assertThat(call(HttpMethod.GET, path, admin, null, 200).path("availableActions").get(0).asText())
                .isEqualTo("ASSIGN");
        call(HttpMethod.POST, path + "/assign", admin, Map.of("assigneeId", admin), 200);
        assertThat(call(HttpMethod.GET, path, admin, null, 200).path("availableActions").get(0).asText())
                .isEqualTo("START");
        call(HttpMethod.POST, path + "/start", admin, null, 200);
        assertThat(call(HttpMethod.GET, path, admin, null, 200).path("availableActions").get(0).asText())
                .isEqualTo("CLOSE");
        review(alert, "REVOKED");
        assertThat(call(HttpMethod.GET, path, admin, null, 200).path("availableActions").get(0).asText())
                .isEqualTo("REVOCATION_WRAPUP");
        call(HttpMethod.POST, notePath, admin, notes, 409);

        jdbc.update("UPDATE violation_work_order SET status='CANCELED',revocation_source=NULL,revocation_review_id=NULL,"
                + "revoked_at=NULL WHERE id=?", orderId);
        var original = jdbc.queryForMap("SELECT * FROM violation_work_order WHERE id=?", orderId);
        var pending = call(HttpMethod.GET, path, admin, null, 200);
        assertThat(pending.path("legacyRevocationNeedsReview").asBoolean()).isTrue();
        assertThat(pending.path("availableActions").get(0).asText()).isEqualTo("RECORD_LEGACY_REVOCATION");
        call(HttpMethod.POST, notePath, driver, notes, 403);
        call(HttpMethod.POST, notePath, admin, Map.of("workDone", " "), 400);
        var saved = call(HttpMethod.POST, notePath, otherAdmin, notes, 200);
        assertThat(saved.path("recordedBy").asLong()).isEqualTo(otherAdmin);
        assertThat(saved.path("recordedAt").isTextual()).isTrue();
        assertThat(saved.path("workDone").asText()).isEqualTo("已联系司机");
        var done = call(HttpMethod.GET, path, admin, null, 200);
        assertThat(done.path("availableActions")).isEmpty();
        assertThat(done.path("legacyRevocationNeedsReview").asBoolean()).isFalse();
        assertThat(done.path("legacyRevocationNote")).isEqualTo(saved);
        call(HttpMethod.POST, notePath, admin, notes, 409);
        assertThat(jdbc.queryForMap("SELECT * FROM violation_work_order WHERE id=?", orderId)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_log WHERE target_id=? AND action='DISPATCH_LEGACY_REVOCATION'",
                Integer.class, orderId)).isEqualTo(1);
    }

    private int score() throws Exception {
        String path = "/api/scores/drivers/" + driver + "?month=" + YearMonth.from(capturedAt);
        return call(HttpMethod.GET, path, admin, null, 200).path("score").asInt();
    }

    private void review(long alertId, String state) throws Exception {
        call(HttpMethod.POST, "/api/alerts/" + alertId + "/reviews", admin, Map.of("status", state, "reason", "处置测试复核"), 200);
    }

    private long alert(String type, String behavior, boolean rollback) {
        return transactions.execute(tx -> {
            String source = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO recognition_record(source_key,source_frame_id,vehicle_id,driver_id,fleet_id,captured_at,
                        pred,behavior_code,max_p,ood,participated,decision_reason,speed_status)
                    VALUES (?,1,?,?,?,?,1,?,0.95,0,1,'EVALUATED','KNOWN')
                    """, source, vehicle, driver, fleet, capturedAt, behavior);
            long recognition = jdbc.queryForObject("SELECT id FROM recognition_record WHERE source_key=?", Long.class, source);
            jdbc.update("""
                    INSERT INTO alert_record(recognition_id,vehicle_id,driver_id,fleet_id,captured_at,behavior_code,max_p,record_type,sms_status)
                    VALUES (?,?,?,?,?,?,0.95,?,'NOT_REQUIRED')
                    """, recognition, vehicle, driver, fleet, capturedAt, behavior, type);
            long id = jdbc.queryForObject("SELECT id FROM alert_record WHERE recognition_id=?", Long.class, recognition);
            var event = new AlertCreatedEvent(id, driver, vehicle, fleet, capturedAt, type, behavior);
            events.publishEvent(event);
            events.publishEvent(event);
            if (rollback) { tx.setRollbackOnly(); }
            return id;
        });
    }

    private JsonNode listFor(long alert) throws Exception {
        var result = call(HttpMethod.GET, API + "/orders?fleetId=" + fleet, admin, null, 200);
        for (JsonNode row : result) {
            if (row.path("alertId").asLong() == alert) { return row; }
        }
        throw new AssertionError("未生成告警工单 " + alert);
    }

    private int assignStatus(long workOrder, long assignee) throws Exception {
        return mvc.perform(request(HttpMethod.POST, order(workOrder) + "/assign")
                        .header("Authorization", "Bearer " + jwt.generateToken(admin, 0))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("assigneeId", assignee))))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode call(HttpMethod method, String path, long actor, Object body, int expected) throws Exception {
        var req = request(method, path).header("Authorization", "Bearer " + jwt.generateToken(actor, 0));
        if (body != null) { req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)); }
        var result = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse();
        return json.readTree(result.getContentAsByteArray()).path("data");
    }

    private long vehicle(String suffix) {
        String plate = key.substring("dispatch-".length()) + suffix;
        jdbc.update("INSERT INTO vehicle(fleet_id,plate_no) VALUES (?,?)", fleet, plate);
        return jdbc.queryForObject("SELECT id FROM vehicle WHERE plate_no=?", Long.class, plate);
    }

    private long user(String suffix, String role) {
        String name = key + "-" + suffix;
        jdbc.update("INSERT INTO sys_user(username,password,name,fleet_id,role) VALUES (?,'unused',?,?,?)", name, name, fleet, role);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, name);
    }

    private static String order(long id) { return API + "/orders/" + id; }
    private static long id(JsonNode node) { return node.path("id").asLong(); }
}
