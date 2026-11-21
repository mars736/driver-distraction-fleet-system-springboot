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
import org.springframework.transaction.support.TransactionTemplate;
import service.alert.AlertDecisionService;
import service.alert.CapturedInference;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.Duration;
import java.util.Scanner;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(25)
class AlertLiveStreamIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @Autowired AlertDecisionService decision;
    @Autowired PlatformTransactionManager transactions;
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
        jdbc.update("DELETE s FROM alert_vehicle_state s JOIN vehicle v ON v.id=s.vehicle_id WHERE v.fleet_id=?", fleet);
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

    private Scanner subscribe(long userId) throws Exception {
        var response = http.send(request("/api/alerts/stream", userId).header("Accept", "text/event-stream").build(),
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
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) { statement.setObject(index + 1, arguments[index]); }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }
}
