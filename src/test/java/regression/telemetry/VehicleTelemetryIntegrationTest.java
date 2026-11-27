package regression.telemetry;

import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import service.alert.AlertDecisionService;
import service.alert.CapturedInference;
import service.alert.VehicleSpeedProvider;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class)
@AutoConfigureMockMvc
class VehicleTelemetryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @Autowired VehicleSpeedProvider speeds;
    @Autowired AlertDecisionService decisions;

    private long fleet;
    private long vehicle;
    private long driver;
    private long otherDriver;
    private long sampledAt;

    @BeforeEach
    void createFixtures() {
        String key = UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO fleet(fleet_no, name) VALUES (?, ?)", key, "定位测试车队");
        fleet = jdbc.queryForObject("SELECT id FROM fleet WHERE fleet_no = ?", Long.class, key);
        jdbc.update("INSERT INTO vehicle(fleet_id, plate_no) VALUES (?, ?)", fleet, key);
        vehicle = jdbc.queryForObject("SELECT id FROM vehicle WHERE plate_no = ?", Long.class, key);
        driver = createDriver("telemetry-" + key, vehicle);
        otherDriver = createDriver("telemetry-other-" + key, null);
        sampledAt = System.currentTimeMillis() - 2000;
    }

    @AfterEach
    void removeFixtures() {
        jdbc.update("DELETE FROM alert_record WHERE vehicle_id = ?", vehicle);
        jdbc.update("DELETE FROM recognition_record WHERE vehicle_id = ?", vehicle);
        jdbc.update("DELETE FROM alert_vehicle_state WHERE vehicle_id = ?", vehicle);
        jdbc.update("DELETE FROM vehicle_stopped_motion_state WHERE vehicle_id = ?", vehicle);
        jdbc.update("DELETE FROM vehicle_stopped_motion_check WHERE vehicle_id = ?", vehicle);
        jdbc.update("DELETE FROM vehicle_telemetry WHERE vehicle_id = ?", vehicle);
        jdbc.update("DELETE FROM driver_attendance WHERE shift_id IN (SELECT id FROM driver_shift WHERE fleet_id=?)", fleet);
        jdbc.update("DELETE FROM driver_shift WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM sys_user WHERE fleet_id = ?", fleet);
        jdbc.update("DELETE FROM vehicle WHERE id = ?", vehicle);
        jdbc.update("DELETE FROM fleet WHERE id = ?", fleet);
    }

    @Test
    void reportedLowSpeedSilencesRecognitionAndExpiresAtFrameTime() throws Exception {
        report(driver, sampledAt, "1.25").andExpect(status().isOk());

        String silentFrame = processFrame(sampledAt + 15_000);
        assertThat(jdbc.queryForObject("SELECT decision_reason FROM recognition_record WHERE source_key = ?",
                String.class, silentFrame)).isEqualTo("LOW_SPEED");
        assertThat(jdbc.queryForObject("SELECT speed_kmh FROM recognition_record WHERE source_key = ?",
                Double.class, silentFrame)).isEqualTo(4.5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_record WHERE vehicle_id = ?",
                Integer.class, vehicle)).isZero();

        String expiredFrame = processFrame(sampledAt + 15_001);
        assertThat(jdbc.queryForObject("SELECT speed_status FROM recognition_record WHERE source_key = ?",
                String.class, expiredFrame)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForList("SELECT record_type FROM alert_record WHERE vehicle_id = ?",
                String.class, vehicle)).containsExactly("SPEED_UNKNOWN");
    }

    @Test
    void samplesAreIdempotentAndSelectedByDriverAndCaptureTime() throws Exception {
        report(driver, sampledAt + 500, "null").andExpect(status().isOk());
        report(driver, sampledAt, "2.5").andExpect(status().isOk());
        report(driver, sampledAt, "12").andExpect(status().isOk());

        assertThat(speeds.speedKmh(vehicle, driver, sampledAt - 1)).isNull();
        assertThat(speeds.speedKmh(vehicle, driver, sampledAt + 499)).isEqualTo(9.0);
        assertThat(speeds.speedKmh(vehicle, driver, sampledAt + 500)).isNull();
        assertThat(speeds.speedKmh(vehicle, otherDriver, sampledAt + 499)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_telemetry WHERE vehicle_id = ?",
                Integer.class, vehicle)).isEqualTo(2);
    }

    @Test
    void onlyBoundDriversCanReportValidSamples() throws Exception {
        long admin = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
        mvc.perform(request(sampledAt, "1")).andExpect(status().isUnauthorized());
        report(admin, sampledAt, "1").andExpect(status().isForbidden());
        report(otherDriver, sampledAt, "1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        report(driver, sampledAt, "-1").andExpect(status().isBadRequest());
        report(driver, sampledAt - 600_000, "1").andExpect(status().isBadRequest());
        report(driver, sampledAt + 60_000, "1").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_telemetry WHERE vehicle_id = ?",
                Integer.class, vehicle)).isZero();
    }

    @Test
    void driverCanReadOnlyTheCurrentlyBoundVehicle() throws Exception {
        String plate = UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO vehicle(fleet_id, plate_no) VALUES (?,?)", fleet, plate);
        long next = jdbc.queryForObject("SELECT id FROM vehicle WHERE plate_no=?", Long.class, plate);
        jdbc.update("INSERT INTO fleet(fleet_no,name) VALUES (?,?)", plate, "另一车队");
        long mismatchedFleet = jdbc.queryForObject("SELECT id FROM fleet WHERE fleet_no=?", Long.class, plate);
        String bearer = "Bearer " + jwt.generateToken(driver, 0);
        String unbound = "Bearer " + jwt.generateToken(otherDriver, 0);
        long admin = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
        String adminBearer = "Bearer " + jwt.generateToken(admin, 0);
        try {
            mvc.perform(get("/api/vehicles/{id}", vehicle).header("Authorization", bearer))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(vehicle));
            mvc.perform(head("/api/vehicles/{id}", vehicle).header("Authorization", bearer))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/vehicles/{id}", next).header("Authorization", bearer))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.data").isEmpty());
            mvc.perform(head("/api/vehicles/{id}", next).header("Authorization", bearer))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/vehicles/{id}", vehicle).header("Authorization", unbound))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/vehicles/{id}", Long.MAX_VALUE).header("Authorization", bearer))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("车辆不存在"));
            mvc.perform(get("/api/vehicles/{id}", next).header("Authorization", adminBearer))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(next));

            jdbc.update("UPDATE sys_user SET vehicle_id=? WHERE id=?", next, driver);
            mvc.perform(get("/api/vehicles/{id}", vehicle).header("Authorization", bearer))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/vehicles/{id}", next).header("Authorization", bearer))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(next));
            jdbc.update("UPDATE vehicle SET fleet_id=? WHERE id=?", mismatchedFleet, next);
            mvc.perform(get("/api/vehicles/{id}", next).header("Authorization", bearer))
                    .andExpect(status().isNotFound());
        } finally {
            jdbc.update("UPDATE sys_user SET vehicle_id=? WHERE id=?", vehicle, driver);
            jdbc.update("DELETE FROM vehicle WHERE id=?", next);
            jdbc.update("DELETE FROM fleet WHERE id=?", mismatchedFleet);
        }
    }

    @Test
    void vehicleListAndManagementRoutesStayAdministratorOnly() throws Exception {
        String bearer = "Bearer " + jwt.generateToken(driver, 0);
        long admin = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
        mvc.perform(get("/api/vehicles/{id}", vehicle)).andExpect(status().isUnauthorized());
        for (String path : new String[]{"/api/vehicles", "/api/vehicles/live-status", "/api/vehicles/" + vehicle + "/track"}) {
            mvc.perform(get(path).header("Authorization", bearer)).andExpect(status().isForbidden());
            mvc.perform(head(path).header("Authorization", bearer)).andExpect(status().isForbidden());
        }
        mvc.perform(put("/api/vehicles/{id}", vehicle).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vehicles").param("fleetId", String.valueOf(fleet))
                        .header("Authorization", "Bearer " + jwt.generateToken(admin, 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void liveStatusUsesCurrentBindingAndHidesStaleSpeedFromAdmin() throws Exception {
        long admin = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
        var query = get("/api/vehicles/live-status").param("fleetId", Long.toString(fleet));
        mvc.perform(query.header("Authorization", "Bearer " + jwt.generateToken(driver, 0)))
                .andExpect(status().isForbidden());

        var adminQuery = get("/api/vehicles/live-status").param("fleetId", Long.toString(fleet))
                .header("Authorization", "Bearer " + jwt.generateToken(admin, 0));
        report(driver, sampledAt - 60_000, "2.5").andExpect(status().isOk());
        mvc.perform(adminQuery).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].online").value(false))
                .andExpect(jsonPath("$.data.list[0].latitude").value(24.48))
                .andExpect(jsonPath("$.data.list[0].speedKmh").isEmpty());

        report(driver, System.currentTimeMillis(), "1.25").andExpect(status().isOk());
        mvc.perform(adminQuery).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].online").value(true))
                .andExpect(jsonPath("$.data.list[0].speedKmh").value(4.5));

        jdbc.update("UPDATE sys_user SET vehicle_id = NULL WHERE id = ?", driver);
        mvc.perform(adminQuery).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].online").value(false))
                .andExpect(jsonPath("$.data.list[0].sampledAt").isEmpty());
    }

    @Test
    void stoppedFleetCheckUsesNewConsecutiveSamplesAndResetsAcrossPeriods() throws Exception {
        report(driver, sampledAt, "2").andExpect(status().isOk());
        report(driver, sampledAt + 1, "2").andExpect(status().isOk());
        assertThat(movementChecks()).isZero();

        long start = stopFleet(true);
        report(driver, start + 10, "2").andExpect(status().isOk());
        report(driver, start + 10, "2").andExpect(status().isOk());
        report(driver, start + 20, "1").andExpect(status().isOk());
        report(driver, start + 30, "2").andExpect(status().isOk());
        assertThat(movementChecks()).isZero();

        report(driver, start + 40, "1.3888888888888888").andExpect(status().isOk());
        report(driver, start + 50, "2").andExpect(status().isOk());
        assertThat(movementChecks()).isZero();
        // 晚到的历史样本仍入库，但不推进连续状态。
        report(driver, start + 45, "2").andExpect(status().isOk());
        assertThat(movementChecks()).isZero();

        report(driver, start + 60, "2").andExpect(status().isOk());
        report(driver, start + 70, "2").andExpect(status().isOk());
        assertThat(movementChecks()).isEqualTo(1);
        mvc.perform(liveStatus()).andExpect(jsonPath("$.data.list[0].coordinateSystem").value("GCJ-02"))
                .andExpect(jsonPath("$.data.list[0].receivedAt").isNumber())
                .andExpect(jsonPath("$.data.list[0].movementCheck.firstSampledAt").value(start + 50))
                .andExpect(jsonPath("$.data.list[0].movementCheck.secondSampledAt").value(start + 60));

        stopFleet(false);
        mvc.perform(liveStatus()).andExpect(jsonPath("$.data.list[0].movementCheck").isEmpty());
        long restarted = stopFleet(true);
        long next = Math.max(restarted, start + 70) + 100;
        report(driver, next, "2").andExpect(status().isOk());
        assertThat(movementChecks()).isEqualTo(1);
        report(driver, next + 1, "2").andExpect(status().isOk());
        assertThat(movementChecks()).isEqualTo(2);
    }

    @Test
    void historicalDoubleBindingIsReportedAndCannotTriggerMovementCheck() throws Exception {
        long start = stopFleet(true);
        jdbc.update("UPDATE sys_user SET vehicle_id=? WHERE id=?", vehicle, otherDriver);
        report(driver, start + 10, "2").andExpect(status().isOk());
        report(otherDriver, start + 20, "2").andExpect(status().isOk());
        report(driver, start + 30, "2").andExpect(status().isOk());
        assertThat(movementChecks()).isZero();
        mvc.perform(liveStatus()).andExpect(jsonPath("$.data.list[0].locationStatus").value("BINDING_CONFLICT"))
                .andExpect(jsonPath("$.data.list[0].online").value(false))
                .andExpect(jsonPath("$.data.list[0].driverId").isEmpty())
                .andExpect(jsonPath("$.data.list[0].latitude").isEmpty());

        mvc.perform(patch("/api/drivers/{id}/vehicle", driver).header("Authorization", adminToken()))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(liveStatus()).andExpect(jsonPath("$.data.list[0].driverId").value(otherDriver))
                .andExpect(jsonPath("$.data.list[0].locationStatus").value("ONLINE"));
        report(otherDriver, start + 40, "2").andExpect(status().isOk());
        assertThat(movementChecks()).isZero();
    }

    @Test
    void concurrentBindingsCanOnlyLeaveOneEnabledDriverAssigned() throws Exception {
        jdbc.update("UPDATE sys_user SET vehicle_id=NULL WHERE id=?", driver);
        for (long id : new long[]{driver, otherDriver}) {
            var at = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            jdbc.update("INSERT INTO driver_shift(driver_id,fleet_id,shift_date,shift_type,starts_at,ends_at) VALUES(?,?,?,'EARLY',?,?)",
                    id, fleet, at.toLocalDate(), at.minusHours(1), at.plusHours(1));
            jdbc.update("INSERT INTO driver_attendance(shift_id,clock_in_at,clock_in_lat,clock_in_lon) "
                    + "SELECT id,?,0,0 FROM driver_shift WHERE driver_id=?", at, id);
        }
        String token = adminToken();
        var ready = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { ready.await(); return bind(driver, token); });
            var b = pool.submit(() -> { ready.await(); return bind(otherDriver, token); });
            ready.countDown();
            assertThat(java.util.List.of(a.get(6, TimeUnit.SECONDS), b.get(6, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE enabled=1 AND vehicle_id=?",
                Integer.class, vehicle)).isEqualTo(1);
    }

    @Test
    void trackSnapshotExcludesLateSamplesAndKeepsCoordinateSource() throws Exception {
        report(driver, sampledAt, "2").andExpect(status().isOk());
        long begin = sampledAt - 10_000;
        var first = mvc.perform(track(begin, sampledAt + 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalPoints").value(1))
                .andExpect(jsonPath("$.data.points[0].coordinateSystem").value("GCJ-02"))
                .andReturn().getResponse();
        long snapshot = json.readTree(first.getContentAsByteArray()).path("data").path("snapshotId").asLong();
        assertThat(snapshot).isPositive();
        report(driver, sampledAt - 3000, "2").andExpect(status().isOk());
        jdbc.update("INSERT INTO vehicle_telemetry(vehicle_id,driver_id,sampled_at,latitude,longitude) VALUES(?,?,?,24.48,118.08)",
                vehicle, driver, sampledAt - 2000);
        mvc.perform(track(begin, sampledAt + 1).param("snapshotId", Long.toString(snapshot)).param("page", "2"))
                .andExpect(jsonPath("$.data.totalPoints").value(1))
                .andExpect(jsonPath("$.data.snapshotId").value(snapshot));
        mvc.perform(track(begin, sampledAt + 1))
                .andExpect(jsonPath("$.data.totalPoints").value(3))
                .andExpect(jsonPath("$.data.coordinateSystem").value("MIXED"))
                .andExpect(jsonPath("$.data.points[1].coordinateSystem").value("UNKNOWN"));
        mvc.perform(liveStatus()).andExpect(jsonPath("$.data.list[0].coordinateSystem").value("GCJ-02"));
    }

    private MockHttpServletRequestBuilder track(long start, long end) {
        return get("/api/vehicles/{id}/track", vehicle).param("startTime", Long.toString(start))
                .param("endTime", Long.toString(end)).header("Authorization", adminToken());
    }

    private int bind(long driverId, String token) throws Exception {
        var response = mvc.perform(patch("/api/drivers/{id}/vehicle", driverId).header("Authorization", token)
                        .param("vehicleId", Long.toString(vehicle))).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).path("code").asInt();
    }

    private long stopFleet(boolean stopped) throws Exception {
        mvc.perform(patch("/api/fleets/{id}/enabled", fleet).header("Authorization", adminToken())
                        .param("enabled", Boolean.toString(!stopped)))
                .andExpect(jsonPath("$.code").value(200));
        long stoppedAt = jdbc.queryForObject("SELECT stopped_at FROM fleet WHERE id=?", Long.class, fleet);
        if (stopped) {
            // 数据库保存的毫秒时间在 Windows 上可能略领先 JVM 当前时间。
            await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(5)).atMost(Duration.ofSeconds(2))
                    .until(() -> System.currentTimeMillis() >= stoppedAt);
        }
        return stoppedAt;
    }

    private MockHttpServletRequestBuilder liveStatus() {
        return get("/api/vehicles/live-status").param("fleetId", Long.toString(fleet)).header("Authorization", adminToken());
    }

    private String adminToken() {
        var user = jdbc.queryForMap("SELECT id, token_version FROM sys_user WHERE username='admin'");
        return "Bearer " + jwt.generateToken(((Number) user.get("id")).longValue(), ((Number) user.get("token_version")).intValue());
    }

    private long movementChecks() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_stopped_motion_check WHERE vehicle_id=?", Long.class, vehicle);
    }

    private long createDriver(String username, Long vehicleId) {
        jdbc.update("""
                INSERT INTO sys_user(username, password, name, fleet_id, role, vehicle_id)
                VALUES (?, 'unused', ?, ?, 'DRIVER', ?)
                """, username, username, fleet, vehicleId);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private ResultActions report(long user, long time, String speed) throws Exception {
        return mvc.perform(request(time, speed).header("Authorization", "Bearer " + jwt.generateToken(user, 0)));
    }

    private MockHttpServletRequestBuilder request(long time, String speed) {
        return post("/api/vehicle-telemetry").contentType(MediaType.APPLICATION_JSON).content("""
                {"vehicleId":%d,"sampledAt":%d,"latitude":24.48,"longitude":118.08,"speedMps":%s}
                """.formatted(vehicle, time, speed));
    }

    private String processFrame(long capturedAt) throws Exception {
        String source = UUID.randomUUID().toString();
        decisions.process(new CapturedInference(source, 1, null, vehicle, driver,
                capturedAt, 1, false, 0.95, -5, 0.1, null), false);
        return source;
    }
}
