package regression.dashboard;

import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class)
@AutoConfigureMockMvc
class DashboardIntegrationTest {
    private static final String ROOT = "/api/dashboard";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired MockMvc mvc;
    private long fleetA, fleetB, vehicleA, vehicleB, driverA, driverB;
    private String admin, driver;
    private LocalDateTime day;

    @BeforeEach
    void fixtures() {
        String key = UUID.randomUUID().toString().substring(0, 12);
        day = ZonedDateTime.now(ZONE).toLocalDate().atStartOfDay();
        fleetA = insert("INSERT INTO fleet(fleet_no,name) VALUES(?,?)", "stats-a-" + key, "Test A");
        fleetB = insert("INSERT INTO fleet(fleet_no,name) VALUES(?,?)", "stats-b-" + key, "Test B");
        vehicleA = insert("INSERT INTO vehicle(fleet_id,plate_no) VALUES(?,?)", fleetA, "SA" + key);
        vehicleB = insert("INSERT INTO vehicle(fleet_id,plate_no) VALUES(?,?)", fleetB, "SB" + key);
        driverA = insert("INSERT INTO sys_user(username,password,name,role,fleet_id,vehicle_id) VALUES(?,'unused','A','DRIVER',?,?)",
                "stats-a-" + key, fleetA, vehicleA);
        driverB = insert("INSERT INTO sys_user(username,password,name,role,fleet_id,vehicle_id) VALUES(?,'unused','B','DRIVER',?,?)",
                "stats-b-" + key, fleetB, vehicleB);
        admin = token(jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class));
        driver = token(driverA);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM alert_record WHERE fleet_id IN (?,?)", fleetA, fleetB);
        jdbc.update("DELETE FROM recognition_record WHERE fleet_id IN (?,?)", fleetA, fleetB);
        jdbc.update("DELETE FROM alert_vehicle_state WHERE vehicle_id IN (?,?)", vehicleA, vehicleB);
        jdbc.update("DELETE FROM sys_user WHERE id IN (?,?)", driverA, driverB);
        jdbc.update("DELETE FROM vehicle WHERE id IN (?,?)", vehicleA, vehicleB);
        jdbc.update("DELETE FROM fleet WHERE id IN (?,?)", fleetA, fleetB);
    }

    @Test
    void fleetOverviewUsesMidnightBoundariesAndSeparatesRejectedAndRevokedRecords() throws Exception {
        frame(fleetA, vehicleA, driverA, day, "c0", false);
        long behavior = frame(fleetA, vehicleA, driverA, day.plusSeconds(1), "c1", false);
        long revoked = frame(fleetA, vehicleA, driverA, day.plusSeconds(2), "c3", false);
        long anomaly = frame(fleetA, vehicleA, driverA, day.plusSeconds(3), "c1", true);
        frame(fleetA, vehicleA, driverA, day.plusSeconds(4), null, false);
        frame(fleetA, vehicleA, driverA, day.minusNanos(1_000_000), "c0", false);
        frame(fleetA, vehicleA, driverA, day.plusDays(1), "c0", false);
        frame(fleetB, vehicleB, driverB, day, "c0", false);
        alert(behavior, fleetA, vehicleA, driverA, day.plusSeconds(1), "SMS_ALERT", "OPEN");
        alert(revoked, fleetA, vehicleA, driverA, day.plusSeconds(2), "BACKLOG", "REVOKED");
        alert(anomaly, fleetA, vehicleA, driverA, day.plusSeconds(3), "SPEED_UNKNOWN", "OPEN");

        mvc.perform(get(ROOT + "/overview").param("fleetId", String.valueOf(fleetA)).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fleetSize.vehicleCount").value(1))
                .andExpect(jsonPath("$.data.fleetSize.driverCount").value(1))
                .andExpect(jsonPath("$.data.recognitions.totalFrames").value(5))
                .andExpect(jsonPath("$.data.recognitions.normalFrames").value(1))
                .andExpect(jsonPath("$.data.recognitions.distractedFrames").value(2))
                .andExpect(jsonPath("$.data.recognitions.oodFrames").value(1))
                .andExpect(jsonPath("$.data.recognitions.unclassifiedFrames").value(1))
                .andExpect(jsonPath("$.data.recognitions.oodRate").value(0.2))
                .andExpect(jsonPath("$.data.recognitions.behaviors[0].behaviorCode").value("c1"))
                .andExpect(jsonPath("$.data.recognitions.behaviors[0].share").value(0.5))
                .andExpect(jsonPath("$.data.alerts.activeViolationCount").value(1))
                .andExpect(jsonPath("$.data.alerts.speedUnknownCount").value(1))
                .andExpect(jsonPath("$.data.alerts.revokedViolationCount").value(1));

        mvc.perform(get(ROOT + "/overview").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recognitions.totalFrames").value(6));
    }

    @Test
    void driverUsesStoredAttributionEvenAfterChangingVehiclesAndReturnsOnlyFiveOwnRecentAlerts() throws Exception {
        long expected = 0;
        for (int i = 0; i < 6; i++) {
            long rec = frame(fleetA, vehicleA, driverA, day, "c1", false);
            expected = alert(rec, fleetA, vehicleA, driverA, day, "SMS_ALERT", "CONFIRMED");
        }
        long other = frame(fleetB, vehicleB, driverB, day.plusSeconds(1), "c1", false);
        alert(other, fleetB, vehicleB, driverB, day.plusSeconds(1), "SMS_ALERT", "OPEN");
        jdbc.update("UPDATE sys_user SET fleet_id=?, vehicle_id=? WHERE id=?", fleetB, vehicleB, driverA);

        mvc.perform(get(ROOT + "/me").param("driverId", String.valueOf(driverB)).header("Authorization", driver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.driverId").value(driverA))
                .andExpect(jsonPath("$.data.recognitions.totalFrames").value(6))
                .andExpect(jsonPath("$.data.alerts.activeViolationCount").value(6))
                .andExpect(jsonPath("$.data.recentAlerts.length()").value(5))
                .andExpect(jsonPath("$.data.recentAlerts[0].id").value(expected))
                .andExpect(jsonPath("$.data.recentAlerts[0].vehicleId").value(vehicleA));
    }

    @Test
    void singleVehicleReadsOnlyItsCountersAndOnlyFromTheCurrentFixedHour() throws Exception {
        frame(fleetA, vehicleA, driverA, day, "c0", false);
        frame(fleetA, vehicleB, driverB, day, "c1", false);
        var hour = ZonedDateTime.now(ZONE).truncatedTo(ChronoUnit.HOURS);
        long current = hour.toInstant().toEpochMilli();
        jdbc.update("INSERT INTO alert_vehicle_state(vehicle_id,state_json) VALUES(?,?)", vehicleA, counter(current));
        String url = ROOT + "/vehicles/" + vehicleA;
        mvc.perform(get(url).header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recognitions.totalFrames").value(1))
                .andExpect(jsonPath("$.data.currentHourDistractionEvents").value(4));
        jdbc.update("UPDATE alert_vehicle_state SET state_json=? WHERE vehicle_id=?", counter(current - 3_600_000), vehicleA);
        mvc.perform(get(url).header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentHourDistractionEvents").value(0));
    }

    @Test
    void rejectsWrongRolesAndInvalidIdsAndHandlesEmptyResults() throws Exception {
        mvc.perform(get(ROOT + "/overview")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(get(ROOT + "/overview").header("Authorization", driver)).andExpect(status().isForbidden());
        mvc.perform(get(ROOT + "/vehicles/" + vehicleA).header("Authorization", driver)).andExpect(status().isForbidden());
        mvc.perform(get(ROOT + "/me").header("Authorization", admin)).andExpect(status().isForbidden());
        mvc.perform(get(ROOT + "/vehicles/0").header("Authorization", admin)).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/vehicles/nope").header("Authorization", admin)).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/vehicles/" + Long.MAX_VALUE).header("Authorization", admin)).andExpect(status().isNotFound());
        mvc.perform(get(ROOT + "/overview").param("fleetId", "").header("Authorization", admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get(ROOT + "/overview").param("fleetId", String.valueOf(Long.MAX_VALUE)).header("Authorization", admin))
                .andExpect(status().isNotFound());
        mvc.perform(get(ROOT + "/me").header("Authorization", driver)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recognitions.oodRate").value(0))
                .andExpect(jsonPath("$.data.recognitions.behaviors").isEmpty())
                .andExpect(jsonPath("$.data.recentAlerts").isEmpty());
    }

    private static String counter(long hour) {
        return "{\"hourStart\":" + hour + ",\"lastCapturedAt\":" + hour + ",\"otherEvents\":4}";
    }

    private String token(long user) { return "Bearer " + jwt.generateToken(user, 0); }

    private long frame(long fleet, long vehicle, long driver, LocalDateTime time, String behavior, boolean ood) {
        return insert("""
                INSERT INTO recognition_record(source_key,source_frame_id,vehicle_id,driver_id,fleet_id,
                    captured_at,pred,behavior_code,ood,participated,decision_reason,speed_status)
                VALUES (?,1,?,?,?,?,1,?,?,0,'TEST','UNKNOWN')
                """, UUID.randomUUID().toString(), vehicle, driver, fleet, time, behavior, ood);
    }

    private long alert(long rec, long fleet, long vehicle, long driver, LocalDateTime time, String type, String status) {
        return insert("""
                INSERT INTO alert_record(recognition_id,vehicle_id,driver_id,fleet_id,captured_at,
                    record_type,sms_status,status) VALUES (?,?,?,?,?,?,'NOT_REQUIRED',?)
                """, rec, vehicle, driver, fleet, time, type, status);
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) { statement.setObject(i + 1, args[i]); }
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
