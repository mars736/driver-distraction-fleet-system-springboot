package regression.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.context.ApplicationEventPublisher;
import service.alert.live.OperationsReminderEvent;
import org.springframework.transaction.support.TransactionTemplate;
import service.alert.AlertDecisionService;
import service.alert.CapturedInference;
import service.telemetry.VehicleOfflineNotifier;
import service.telemetry.VehicleTelemetryService;
import dto.telemetry.VehicleTelemetryRequest;
import mapper.VehicleTelemetryMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Scanner;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "telemetry.offline-monitor.enabled=false")
@Timeout(25)
class AlertLiveStreamIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @Autowired AlertDecisionService decision;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationEventPublisher events;
    @Autowired VehicleOfflineNotifier offlineNotifier;
    @Autowired VehicleTelemetryService telemetryService;
    @Autowired VehicleTelemetryMapper telemetry;

    private long fleet;
    private long driverA;
    private long driverB;
    private long admin;
    private long unknownRole;
    private HttpClient http;

    @BeforeEach
    void fixtures() {
        fleet = insert("INSERT INTO fleet(fleet_no,name) VALUES (?, '实时告警验收')", "live-" + key());
        driverA = user("DRIVER");
        driverB = user("DRIVER");
        admin = user("PLATFORM_ADMIN");
        unknownRole = user("UNKNOWN");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void cleanup() {
        if (http != null) { http.shutdownNow(); }
        jdbc.update("DELETE FROM alert_record WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM recognition_record WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM alert_vehicle_state WHERE vehicle_id IN (SELECT id FROM vehicle WHERE fleet_id=?)", fleet);
        jdbc.update("DELETE FROM vehicle_task_offline_notice WHERE vehicle_id IN (SELECT id FROM vehicle WHERE fleet_id=?)", fleet);
        jdbc.update("DELETE FROM transport_task WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM vehicle_stopped_motion_check WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM vehicle_stopped_motion_state WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM vehicle_telemetry WHERE vehicle_id IN (SELECT id FROM vehicle WHERE fleet_id=?)", fleet);
        jdbc.update("DELETE FROM sys_user WHERE id IN (?,?,?,?)", driverA, driverB, admin, unknownRole);
        jdbc.update("DELETE FROM vehicle WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM fleet WHERE id=?", fleet);
    }

    @Test
    void sendsCommittedAlertsToAdminAndOwnerOnly() throws Exception {
        try (var all = subscribe(admin); var own = subscribe(driverA)) {
            fire(input(driverB));
            long otherAlert = newestAlert(driverB);
            fire(input(driverA));
            long ownAlert = newestAlert(driverA);
            assertThat(nextAlert(all)).isEqualTo(otherAlert);
            assertThat(nextAlert(all)).isEqualTo(ownAlert);
            assertThat(nextAlert(own)).isEqualTo(ownAlert);
        }
    }

    @Test
    void nativeEventSourceTicketCanOnlyOpenItsOwnersStreamAndCannotBeReused() throws Exception {
        String ticket = issueTicket(driverA);
        assertThat(http.send(request("/api/alerts?ticket=" + ticket, null).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);

        String url = "/api/alerts/stream?ticket=" + ticket;
        try (var own = subscribe(url, null)) {
            fire(input(driverB));
            fire(input(driverA));
            assertThat(nextAlert(own)).isEqualTo(newestAlert(driverA));
            var replay = http.send(request(url, null).header("Accept", "text/event-stream").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(replay.statusCode()).isEqualTo(401);
            assertThat(json.readTree(replay.body()).path("code").asInt()).isEqualTo(401);
        }
    }

    @Test
    void ticketIssuanceRequiresAnAuthorizedAccountAndRedemptionRechecksTokenVersion() throws Exception {
        for (Long user : java.util.Arrays.asList(null, unknownRole)) {
            var denied = http.send(request("/api/alerts/stream-ticket", user)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(user == null ? 401 : 403);
        }
        String ticket = issueTicket(driverA);
        jdbc.update("UPDATE sys_user SET token_version=1 WHERE id=?", driverA);
        var response = http.send(request("/api/alerts/stream?ticket=" + ticket, null)
                        .header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void suppressesRolledBackAndAlreadyPersistedEvents() throws Exception {
        try (var all = subscribe(admin)) {
            var rolledBack = input(driverA);
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                fire(rolledBack);
                tx.setRollbackOnly();
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_record WHERE fleet_id=?", Long.class, fleet))
                    .isZero();
            var saved = input(driverA);
            fire(saved);
            long first = newestAlert(driverA);
            fire(saved);
            fire(input(driverA));
            long second = newestAlert(driverA);
            assertThat(nextAlert(all)).isEqualTo(first);
            assertThat(nextAlert(all)).isEqualTo(second);
        }
    }

    @Test
    void reconnectReadsMissedAlertsFromExistingOwnerScopedList() throws Exception {
        try (var first = subscribe(driverA)) {
            fire(input(driverA));
            assertThat(nextAlert(first)).isEqualTo(newestAlert(driverA));
        }
        fire(input(driverA));
        long missed = newestAlert(driverA);
        fire(input(driverB));
        var live = input(driverA);
        try (var second = subscribe(driverA)) {
            var response = http.send(request("/api/alerts?fleetId=" + fleet, driverA).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var data = json.readTree(response.body()).path("data");
            assertThat(data.path("total").asLong()).isEqualTo(2);
            assertThat(data.path("list").get(0).path("id").asLong()).isEqualTo(missed);
            fire(live);
            assertThat(nextAlert(second)).isEqualTo(newestAlert(driverA));
        }
    }

    @Test
    void limitsConnectionsPerAccountWithoutBlockingAnotherDriver() throws Exception {
        try (var first = subscribe(driverA); var second = subscribe(driverA);
             var third = subscribe(driverA); var fourth = subscribe(driverA); var other = subscribe(driverB)) {
            var excess = http.send(request("/api/alerts/stream", driverA).header("Accept", "text/event-stream").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(excess.statusCode()).isEqualTo(429);
            assertThat(json.readTree(excess.body()).path("code").asInt()).isEqualTo(429);
            fire(input(driverB));
            assertThat(nextAlert(other)).isEqualTo(newestAlert(driverB));
        }
    }

    @Test
    void rejectsUnauthorizedRequestsAndStopsDeliveringAfterTokenVersionChanges() throws Exception {
        var anonymous = http.send(request("/api/alerts/stream", null).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        var forbidden = http.send(request("/api/alerts/stream", unknownRole).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(forbidden.statusCode()).isEqualTo(403);
        var head = http.send(request("/api/alerts/stream", driverA).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(head.statusCode()).isEqualTo(405);
        assertThat(head.headers().firstValue("Allow")).hasValue("GET");
        try (var own = subscribe(driverA)) {
            jdbc.update("UPDATE sys_user SET token_version=1 WHERE id=?", driverA);
            fire(input(driverA));
            assertThat(own.hasNextLine()).isFalse();
        }
    }

    @Test
    void reportsOfflineOnlyToAdminsAndOnlyOncePerStalePosition() throws Exception {
        long vehicleId = insert("INSERT INTO vehicle(fleet_id,plate_no) VALUES (?,?)", fleet, "O" + key());
        jdbc.update("UPDATE sys_user SET vehicle_id=? WHERE id=?", vehicleId, driverA);
        long now = System.currentTimeMillis();
        long oldSample = now - 30_000;
        var departed = java.time.Instant.ofEpochMilli(now - 35_000).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime();
        long task = insert("""
                INSERT INTO transport_task(driver_id,vehicle_id,fleet_id,task_type,description,starts_at,ends_at,
                    status,created_by,created_at,departed_at) VALUES (?,?,?,'TEST','测试',?,?,'RUNNING',?,?,?)
                """, driverA, vehicleId, fleet, departed, departed.plusHours(1), admin, departed, departed);
        String sampleSql = """
                INSERT INTO vehicle_telemetry(vehicle_id,driver_id,sampled_at,latitude,longitude,speed_kmh)
                VALUES (?, ?, ?, 24.48, 118.08, 12)
                """;
        jdbc.update(sampleSql, vehicleId, driverA, oldSample - 10_000);
        offlineNotifier.notifyIfStillOffline(vehicleId, driverA, task, now);
        assertThat(offlineNoticeCount(vehicleId)).isZero();
        assertThat(telemetry.findOfflineCandidates(java.util.List.of(vehicleId), now - 15_000)).isEmpty();
        jdbc.update(sampleSql, vehicleId, driverA, oldSample);
        assertThat(telemetry.findOfflineCandidates(java.util.List.of(vehicleId), now - 15_000))
                .containsExactly(new VehicleTelemetryMapper.OfflineCandidate(vehicleId, driverA, task));
        try (var all = subscribe(admin); var driver = subscribe(driverA)) {
            offlineNotifier.notifyIfStillOffline(vehicleId, driverA, task, now);
            offlineNotifier.notifyIfStillOffline(vehicleId, driverA, task, now);
            var event = nextEvent(all, "vehicle-offline");
            assertThat(event.path("vehicleId").asLong()).isEqualTo(vehicleId);
            assertThat(event.path("taskId").asLong()).isEqualTo(task);
            assertThat(event.path("driverId").asLong()).isEqualTo(driverA);
            assertThat(telemetry.findOfflineCandidates(java.util.List.of(vehicleId), now - 15_000)).isEmpty();

            long newSample = now - 1000;
            jdbc.update(sampleSql, vehicleId, driverA, newSample);
            offlineNotifier.notifyIfStillOffline(vehicleId, driverA, task, now);
            assertThat(offlineNoticeCount(vehicleId)).isEqualTo(1);

            offlineNotifier.notifyIfStillOffline(vehicleId, driverA, task, now + 20_000);
            assertThat(nextEvent(all, "vehicle-offline").path("sampledAt").asLong()).isEqualTo(newSample);
            jdbc.update("UPDATE transport_task SET status='COMPLETED' WHERE id=?", task);
            jdbc.update(sampleSql, vehicleId, driverA, now);
            offlineNotifier.notifyIfStillOffline(vehicleId, driverA, task, now + 20_000);
            assertThat(offlineNoticeCount(vehicleId)).isEqualTo(2);
            fire(input(driverA));
            assertThat(nextAlert(driver)).isEqualTo(newestAlert(driverA));
        }
    }

    @Test
    void operationsRemindersAreEmittedAfterCommitAndOnlyToAdmins() throws Exception {
        try (var adminStream = subscribe(admin); var driverStream = subscribe(driverA)) {
            var tx = new TransactionTemplate(transactions);
            tx.executeWithoutResult(status -> {
                events.publishEvent(new OperationsReminderEvent(-1, "MAINTENANCE", 10, fleet, "VEHICLE", 6, "DUE"));
                status.setRollbackOnly();
            });
            tx.executeWithoutResult(status -> events.publishEvent(
                    new OperationsReminderEvent(19, "CERTIFICATE", 10, fleet, "DRIVER", driverA, "EXPIRED")));
            assertThat(nextEvent(adminStream, "operations-reminder").path("id").asLong()).isEqualTo(19);
            fire(input(driverA));
            assertThat(nextAlert(driverStream)).isEqualTo(newestAlert(driverA));
        }
    }

    @Test
    void stoppedFleetMotionIsPublishedOnceAndOnlyToAdministrators() throws Exception {
        long vehicleId = insert("INSERT INTO vehicle(fleet_id,plate_no) VALUES (?,?)", fleet, "M" + key());
        jdbc.update("UPDATE sys_user SET vehicle_id=? WHERE id=?", vehicleId, driverA);
        long time = System.currentTimeMillis() - 1_000;
        jdbc.update("UPDATE fleet SET enabled=0,stopped_at=? WHERE id=?", time - 100, fleet);
        try (var adminStream = subscribe(admin); var driverStream = subscribe(driverA)) {
            reportMoving(vehicleId, time);
            reportMoving(vehicleId, time);
            reportMoving(vehicleId, time + 100);
            reportMoving(vehicleId, time + 200);
            var check = nextEvent(adminStream, "stopped-fleet-motion");
            assertThat(check.path("driverId").asLong()).isEqualTo(driverA);
            assertThat(check.path("firstSampledAt").asLong()).isEqualTo(time);
            assertThat(check.path("secondSampledAt").asLong()).isEqualTo(time + 100);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_stopped_motion_check WHERE fleet_id=?",
                    Long.class, fleet)).isEqualTo(1);
            fire(input(driverA));
            assertThat(nextAlert(driverStream)).isEqualTo(newestAlert(driverA));
        }
    }

    private void reportMoving(long vehicleId, long sampledAt) {
        telemetryService.report(driverA, new VehicleTelemetryRequest(vehicleId, sampledAt,
                java.math.BigDecimal.valueOf(24.48), java.math.BigDecimal.valueOf(118.08), java.math.BigDecimal.valueOf(2)));
    }

    private long offlineNoticeCount(long vehicleId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_task_offline_notice WHERE vehicle_id=?",
                Long.class, vehicleId);
    }

    private com.fasterxml.jackson.databind.JsonNode nextEvent(Scanner scanner, String event) throws Exception {
        while (scanner.hasNextLine()) {
            if (scanner.nextLine().equals("event:" + event)) {
                return json.readTree(scanner.nextLine().substring(5));
            }
        }
        throw new AssertionError("事件流提前关闭");
    }

    private String issueTicket(long userId) throws Exception {
        var response = http.send(request("/api/alerts/stream-ticket", userId).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        var data = json.readTree(response.body()).path("data");
        assertThat(data.path("expiresAt").asText()).isNotBlank();
        return data.path("ticket").asText();
    }

    private Scanner subscribe(long userId) throws Exception {
        return subscribe("/api/alerts/stream", userId);
    }

    private Scanner subscribe(String url, Long userId) throws Exception {
        var response = http.send(request(url, userId).header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).startsWith("text/event-stream"));
        InputStream body = response.body();
        var scanner = new Scanner(body, StandardCharsets.UTF_8);
        try {
            assertThat(scanner.nextLine()).isEqualTo("event:ready");
            while (scanner.hasNextLine() && !scanner.nextLine().isEmpty()) { /* consume ready block */ }
            return scanner;
        } catch (RuntimeException | AssertionError e) {
            scanner.close();
            throw e;
        }
    }

    private long nextAlert(Scanner scanner) throws Exception {
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (line.startsWith("data:")) {
                var event = json.readTree(line.substring(5));
                assertThat(event.has("keyFramePath")).isFalse();
                return event.path("id").asLong();
            }
        }
        throw new AssertionError("告警流提前关闭");
    }

    private HttpRequest.Builder request(String path, Long userId) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8));
        if (userId != null) { request.header("Authorization", "Bearer " + jwt.generateToken(userId, 0)); }
        return request;
    }

    private long newestAlert(long driver) {
        return jdbc.queryForObject("SELECT MAX(id) FROM alert_record WHERE fleet_id=? AND driver_id=?", Long.class, fleet, driver);
    }

    private void fire(CapturedInference frame) {
        try { decision.process(frame, false); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private CapturedInference input(long driver) {
        long vehicle = insert("INSERT INTO vehicle(fleet_id,plate_no) VALUES (?,?)", fleet, "L" + key());
        return new CapturedInference(UUID.randomUUID().toString(), vehicle, null, vehicle, driver,
                System.currentTimeMillis(), 0, false, .99, -5, .1, null);
    }

    private long user(String role) {
        return insert("INSERT INTO sys_user(username,password,name,role,fleet_id) VALUES (?,'unused','fixture',?,?)",
                "live-" + key(), role, fleet);
    }

    private static String key() { return UUID.randomUUID().toString().substring(0, 12); }

    private long insert(String sql, Object... arguments) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int index = 0; index < arguments.length; index++) { statement.setObject(index + 1, arguments[index]); }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }
}
