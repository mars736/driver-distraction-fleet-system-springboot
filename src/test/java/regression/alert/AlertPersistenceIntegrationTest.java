package regression.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import com.sun.net.httpserver.HttpServer;
import entity.AlertRecord;
import inferworker.*;
import mapper.AlertDecisionMapper;
import mapper.VehicleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import service.alert.*;
import service.alert.rule.AlertRuleEngine;
import service.alert.rule.VehicleAlertState;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 显式开启后在已建表的本机 MySQL 上验证；所有业务夹具在结束后删除。 */
@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class,
        properties = "alert.retry-delay-ms=10")
@Import(AlertPersistenceIntegrationTest.SpeedConfig.class)
class AlertPersistenceIntegrationTest {
    @TempDir static Path uploads;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("file.upload-dir", () -> uploads.toString());
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AlertDecisionService service;
    @Autowired SmsDispatcher dispatcher;
    @Autowired AlertDecisionMapper mapper;
    @Autowired VehicleMapper vehicles;
    @Autowired AlertRuleEngine engine;
    @Autowired ObjectMapper json;
    @Autowired KeyFrameStore keyFrames;
    @Autowired MutableSpeed speed;
    @Autowired PlatformTransactionManager transactions;
    @Autowired InferFrameQueue queue;
    @Autowired InferWorkerProperties inferProperties;
    private Long fleetId;
    private Long vehicleId;
    private Long driverId;
    private long timestamp;

    @BeforeEach
    void fixtures() {
        speed.value = 20.0;
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        fleetId = insert("INSERT INTO fleet (fleet_no, name) VALUES (?, ?)", "alert-test-" + suffix, "告警回归测试");
        vehicleId = insert("INSERT INTO vehicle (fleet_id, plate_no) VALUES (?, ?)", fleetId, "TEST" + suffix.substring(0, 10));
        driverId = insert("INSERT INTO sys_user (username, password, name, phone, fleet_id, role, vehicle_id)"
                        + " VALUES (?, ?, ?, ?, ?, 'DRIVER', ?)", "alert-test-" + suffix,
                "fixture-account-no-login", "告警回归测试", "test-recipient", fleetId, vehicleId);
        timestamp = Instant.parse("2026-11-11T02:00:00Z").toEpochMilli();
    }

    @AfterEach
    void cleanup() throws IOException {
        if (vehicleId != null) {
            for (String file : jdbc.queryForList("SELECT DISTINCT key_frame_path FROM recognition_record"
                    + " WHERE vehicle_id=? AND key_frame_path IS NOT NULL", String.class, vehicleId)) {
                Files.deleteIfExists(uploads.resolve(file));
            }
            jdbc.update("DELETE FROM alert_record WHERE vehicle_id=?", vehicleId);
            jdbc.update("DELETE FROM recognition_record WHERE vehicle_id=?", vehicleId);
            jdbc.update("DELETE FROM alert_vehicle_state WHERE vehicle_id=?", vehicleId);
        }
        if (driverId != null) { jdbc.update("DELETE FROM sys_user WHERE id=?", driverId); }
        if (vehicleId != null) { jdbc.update("DELETE FROM vehicle WHERE id=?", vehicleId); }
        if (fleetId != null) { jdbc.update("DELETE FROM fleet WHERE id=?", fleetId); }
    }

    @Test
    void fifthFrameCreatesOneLinkedAlertKeyFrameAndSimulatedSms() throws Exception {
        List<AlertRecord> alerts = List.of();
        for (int i = 0; i < 5; i++) { alerts = service.process(input(i % 2 == 0 ? 1 : 3, false), false); }
        assertThat(alerts).hasSize(1);
        AlertRecord alert = alerts.get(0);
        assertThat(alert.getRecordType()).isEqualTo("SMS_ALERT");
        assertThat(alert.getSmsStatus()).isEqualTo("PENDING");
        assertThat(Files.readAllBytes(uploads.resolve(alert.getKeyFramePath()))).isEqualTo(jpeg());
        dispatcher.dispatch(alerts);
        assertThat(count("recognition_record")).isEqualTo(5);
        assertThat(count("alert_record")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sms_status FROM alert_record WHERE id=?", String.class, alert.getId()))
                .isEqualTo("SIMULATED");
    }

    @Test
    void duplicateInputDoesNotDoubleCountOrResend() throws Exception {
        for (int i = 0; i < 4; i++) { service.process(input(1, false), false); }
        CapturedInference fifth = input(1, false);
        dispatcher.dispatch(service.process(fifth, false));
        assertThat(service.process(fifth, false)).isEmpty();
        assertThat(count("recognition_record")).isEqualTo(5);
        assertThat(count("alert_record")).isEqualTo(1);
        assertThat(state().getSmsFrames()).isEqualTo(5);
    }

    @Test
    void stateRestoresIntoNewServiceInstance() throws Exception {
        for (int i = 0; i < 4; i++) { service.process(input(1, false), false); }
        AlertDecisionService restarted = new AlertDecisionService(mapper, vehicles, speed, engine, json, keyFrames);
        CapturedInference fifth = input(3, false);
        List<AlertRecord> alerts = new TransactionTemplate(transactions).execute(status -> {
            try { return restarted.process(fifth, false); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        });
        assertThat(alerts).hasSize(1);
        assertThat(count("alert_record")).isEqualTo(1);
    }

    @Test
    void rollbackRestoresCountersAndRemovesKeyFrameThenSameInputCanRetry() throws Exception {
        for (int i = 0; i < 4; i++) { service.process(input(1, false), false); }
        CapturedInference fifth = input(1, false);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            try { service.process(fifth, false); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            throw new IllegalStateException("Forced test rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("recognition_record")).isEqualTo(4);
        assertThat(count("alert_record")).isZero();
        assertThat(state().getSmsFrames()).isEqualTo(4);
        try (var files = Files.walk(uploads)) {
            assertThat(files.filter(Files::isRegularFile).count()).isZero();
        }
        assertThat(service.process(fifth, false)).hasSize(1);
        assertThat(count("recognition_record")).isEqualTo(5);
    }

    @Test
    void concurrentDuplicateIsSerializedByVehicleRowLock() throws Exception {
        for (int i = 0; i < 4; i++) { service.process(input(1, false), false); }
        CapturedInference fifth = input(1, false);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> service.process(fifth, false));
            var second = threads.submit(() -> service.process(fifth, false));
            assertThat(first.get(5, TimeUnit.SECONDS)).hasSize(1);
            assertThat(second.get(5, TimeUnit.SECONDS)).hasSize(1);
        }
        assertThat(count("recognition_record")).isEqualTo(5);
        assertThat(count("alert_record")).isEqualTo(1);
    }

    @Test
    void unknownSpeedCreatesOneIndependentAnomalyAndStillAllowsPhoneAlert() throws Exception {
        speed.value = null;
        for (int i = 0; i < 5; i++) { dispatcher.dispatch(service.process(input(1, false), false)); }
        assertThat(count("alert_record")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_record WHERE vehicle_id=?"
                + " AND record_type='SPEED_UNKNOWN' AND behavior_code IS NULL AND sms_status='NOT_REQUIRED'",
                Integer.class, vehicleId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recognition_record WHERE vehicle_id=?"
                + " AND speed_status='UNKNOWN' AND participated=1", Integer.class, vehicleId)).isEqualTo(5);
    }

    @Test
    void oodAndLowSpeedKeepRecognitionMetadataWithoutViolations() throws Exception {
        service.process(input(1, true), false);
        speed.value = 5.0;
        for (int i = 0; i < 6; i++) { service.process(input(1, false), false); }
        assertThat(count("alert_record")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recognition_record WHERE vehicle_id=?"
                + " AND participated=0", Integer.class, vehicleId)).isEqualTo(7);
    }

    @Test
    void actualWorkerCallsMockHttpInferenceThenAsyncListenerPersistsAndSimulatesSms() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger filesSeen = new AtomicInteger();
        server.createContext("/predict", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            int count = (int) Pattern.compile("name=\"files\"").matcher(
                    new String(body, StandardCharsets.ISO_8859_1)).results().count();
            filesSeen.addAndGet(count);
            List<Map<String, Object>> results = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                results.add(Map.of("pred", 1, "class_name", "mock", "max_p", .99,
                        "energy", -3, "knn_dist", .1, "is_ood", false));
            }
            byte[] response = json.writeValueAsBytes(Map.of("results", results));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        String originalUrl = inferProperties.getFastapiUrl();
        server.start();
        try {
            inferProperties.setFastapiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/predict");
            for (int i = 0; i < 5; i++) {
                queue.offer(new InferFrame(i, 1L, vehicleId, driverId, timestamp++, jpeg()));
            }
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(count("recognition_record")).isEqualTo(5);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_record WHERE vehicle_id=?"
                        + " AND record_type='SMS_ALERT' AND sms_status='SIMULATED'", Integer.class, vehicleId))
                        .isEqualTo(1);
            });
            assertThat(filesSeen.get()).isEqualTo(5);
        } finally {
            inferProperties.setFastapiUrl(originalUrl);
            server.stop(0);
        }
    }

    private CapturedInference input(int pred, boolean ood) throws IOException {
        return new CapturedInference(UUID.randomUUID().toString(), timestamp, 1L, vehicleId,
                driverId, timestamp++, pred, ood, .99, -3, .1, jpeg());
    }

    private byte[] jpeg() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpg", bytes);
        return bytes.toByteArray();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE vehicle_id=?", Integer.class, vehicleId);
    }

    private VehicleAlertState state() throws IOException {
        return json.readValue(jdbc.queryForObject("SELECT state_json FROM alert_vehicle_state WHERE vehicle_id=?",
                String.class, vehicleId), VehicleAlertState.class);
    }

    private long insert(String sql, Object... parameters) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < parameters.length; i++) { statement.setObject(i + 1, parameters[i]); }
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    static class MutableSpeed implements VehicleSpeedProvider {
        volatile Double value = 20.0;
        @Override public Double speedKmh(Long vehicleId, long capturedAt) { return value; }
    }

    @TestConfiguration
    static class SpeedConfig {
        @Bean @Primary MutableSpeed testSpeedProvider() { return new MutableSpeed(); }
    }
}
