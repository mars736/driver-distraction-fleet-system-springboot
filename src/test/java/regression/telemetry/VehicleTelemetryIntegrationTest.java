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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class)
@AutoConfigureMockMvc
class VehicleTelemetryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
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
        jdbc.update("DELETE FROM vehicle_telemetry WHERE vehicle_id = ?", vehicle);
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
