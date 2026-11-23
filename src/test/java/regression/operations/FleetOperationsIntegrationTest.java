package regression.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import service.operations.OperationsReminderService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class,
        properties = "telemetry.offline-monitor.enabled=false")
@AutoConfigureMockMvc
class FleetOperationsIntegrationTest {
    private static final String WORK = "/api/operations/workforce";
    private static final String CARE = "/api/operations/fleet";
    private static final String OPS = "/api/operations";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtUtil jwt;
    @Autowired OperationsReminderService reminders;

    private long fleet;
    private long vehicle;
    private long driver;
    private long otherDriver;
    private long admin;
    private LocalDateTime now;
    private LocalDate today;
    private String key;

    @BeforeEach
    void fixtures() {
        key = UUID.randomUUID().toString().substring(0, 8);
        now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.SECONDS);
        today = now.toLocalDate();
        jdbc.update("INSERT INTO fleet(fleet_no,name) VALUES (?,?)", key, "运营测试车队");
        fleet = jdbc.queryForObject("SELECT id FROM fleet WHERE fleet_no=?", Long.class, key);
        vehicle = vehicle("a");
        driver = user("d", "DRIVER", vehicle);
        otherDriver = user("o", "DRIVER", null);
        admin = user("m", "PLATFORM_ADMIN", null);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM operation_reminder WHERE fleet_id=?", fleet);
        jdbc.update("DELETE c FROM operation_certificate c JOIN vehicle v ON v.id=c.subject_id WHERE c.subject_type='VEHICLE' AND v.fleet_id=?", fleet);
        jdbc.update("DELETE c FROM operation_certificate c JOIN sys_user u ON u.id=c.subject_id WHERE c.subject_type='DRIVER' AND u.fleet_id=?", fleet);
        for (String table : List.of("vehicle_odometer", "vehicle_maintenance_plan", "vehicle_repair", "fuel_record")) {
            jdbc.update("DELETE FROM " + table + " WHERE vehicle_id IN (SELECT id FROM vehicle WHERE fleet_id=?)", fleet);
        }
        jdbc.update("DELETE FROM driver_attendance WHERE shift_id IN (SELECT id FROM driver_shift WHERE fleet_id=?)", fleet);
        jdbc.update("DELETE FROM driver_shift WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM driver_leave WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM transport_task WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM operation_log WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM sys_user WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM vehicle WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM fleet WHERE id=?", fleet);
    }

    @Test
    void activeShiftAllowsExactlyOneConcurrentDispatchAndCrossDayAttendanceDeduplicates() throws Exception {
        var starts = now.minusMinutes(20);
        var ends = now.plusHours(4);
        var shift = call(HttpMethod.PUT, WORK + "/shifts", admin, Map.of("driverId", driver, "date", starts.toLocalDate(),
                "type", "LATE", "startsAt", starts, "endsAt", ends), 200);
        var trip = Map.of("driverId", driver, "vehicleId", vehicle, "type", "运输",
                "description", "跨天运输", "startsAt", now.minusMinutes(1), "endsAt", now.plusDays(1));
        call(HttpMethod.POST, WORK + "/tasks", admin, trip, 409);
        var point = Map.of("latitude", 24.48, "longitude", 118.08);
        var punch = Map.of("shiftDate", starts.toLocalDate(), "position", point);
        call(HttpMethod.POST, WORK + "/attendance/in", driver, punch, 200);
        call(HttpMethod.POST, WORK + "/attendance/in", driver, punch, 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM driver_attendance WHERE shift_id=?", Integer.class, id(shift))).isEqualTo(1);
        assertThat(call(HttpMethod.GET, WORK + "/on-duty?fleetId=" + fleet, admin, null, 200).get(0).path("driverId").asLong())
                .isEqualTo(driver);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> rawStatus(HttpMethod.POST, WORK + "/tasks", admin, trip));
            var second = pool.submit(() -> rawStatus(HttpMethod.POST, WORK + "/tasks", admin, trip));
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }

        var tasks = call(HttpMethod.GET, WORK + "/tasks?fleetId=" + fleet, admin, null, 200);
        assertThat(tasks).hasSize(1);
        long task = id(tasks.get(0));
        call(HttpMethod.POST, WORK + "/tasks/" + task + "/departure", otherDriver, point, 404);
        call(HttpMethod.POST, WORK + "/tasks/" + task + "/departure", driver, point, 200);
        assertThat(call(HttpMethod.GET, WORK + "/status", driver, null, 200).asText()).isEqualTo("ON_TASK");
        call(HttpMethod.POST, WORK + "/attendance/out", driver, punch, 409);
        var completed = call(HttpMethod.POST, WORK + "/tasks/" + task + "/completion", driver, point, 200);
        assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        call(HttpMethod.POST, WORK + "/attendance/out", driver, punch, 200);

        jdbc.update("UPDATE transport_task SET departed_at=?,ended_at=? WHERE id=?",
                today.minusDays(1).atTime(23, 0), today.atTime(1, 0), task);
        var totals = call(HttpMethod.GET, WORK + "/attendance/summary?start=" + today.minusDays(1)
                + "&end=" + today.plusDays(1), driver, null, 200);
        assertThat(totals.path("taskDays").asInt()).isEqualTo(2);
        assertThat(totals.path("punchDays").asInt()).isEqualTo(1);
        assertThat(totals.path("attendedDays").asInt()).isEqualTo(2);
        assertThat(totals.path("lateDays").asInt()).isEqualTo(1);
        assertThat(totals.path("earlyLeaveDays").asInt()).isEqualTo(1);
    }

    @Test
    void approvedLeaveIsScopedAvoidsDoubleDecisionAndBlocksDuty() throws Exception {
        var leave = call(HttpMethod.POST, WORK + "/leaves", driver, Map.of("type", "PERSONAL",
                "startsAt", now.minusMinutes(1), "endsAt", now.plusHours(2), "reason", "请假测试"), 200);
        var decision = Map.of("approved", true, "note", "同意");
        String path = WORK + "/leaves/" + id(leave) + "/decision";
        call(HttpMethod.POST, path, otherDriver, decision, 403);
        assertThat(call(HttpMethod.POST, path, admin, decision, 200).path("status").asText()).isEqualTo("APPROVED");
        call(HttpMethod.POST, path, admin, decision, 409);
        call(HttpMethod.GET, WORK + "/leaves?driverId=" + driver, otherDriver, null, 404);
        assertThat(call(HttpMethod.GET, WORK + "/status", driver, null, 200).asText()).isEqualTo("ON_LEAVE");
        var totals = call(HttpMethod.GET, WORK + "/attendance/summary?start=" + today.minusDays(1)
                + "&end=" + today.plusDays(2), driver, null, 200);
        assertThat(totals.path("leaveDays").asInt()).isBetween(1, 2);
    }

    @Test
    void maintenanceAndCertificatesProduceDurableRemindersWithIndependentCycles() throws Exception {
        String path = CARE + "/vehicles/" + vehicle;
        call(HttpMethod.PUT, path + "/odometer", admin, Map.of("odometerKm", 1510, "measuredAt", now.minusMinutes(5)), 200);
        call(HttpMethod.PUT, path + "/odometer", admin, Map.of("odometerKm", 1500, "measuredAt", now.minusMinutes(1)), 409);
        long plan = id(call(HttpMethod.POST, path + "/maintenance", admin,
                Map.of("name", "更换滤芯", "intervalKm", 500, "lastServiceOdometerKm", 1000), 200));
        long certificate = id(certificate(today.minusDays(1)));
        reminders.scan();
        reminders.scan();
        var history = call(HttpMethod.GET, OPS + "/reminders?fleetId=" + fleet, admin, null, 200);
        assertThat(history).hasSize(2);
        assertThat(find(history, "MAINTENANCE").path("stage").asText()).isEqualTo("DUE");
        assertThat(find(history, "CERTIFICATE").path("stage").asText()).isEqualTo("EXPIRED");
        call(HttpMethod.GET, OPS + "/reminders", driver, null, 403);

        var completed = call(HttpMethod.POST, CARE + "/maintenance/" + plan + "/complete", admin,
                Map.of("cycle", 1, "odometerKm", 1510), 200);
        assertThat(completed.path("cycle").asInt()).isEqualTo(2);
        assertThat(completed.path("nextOdometerKm").asDouble()).isEqualTo(2010);
        call(HttpMethod.POST, CARE + "/maintenance/" + plan + "/complete", admin, Map.of("cycle", 1, "odometerKm", 1510), 409);
        assertThat(id(certificate(today.plusDays(5)))).isEqualTo(certificate);

        reminders.scan();
        reminders.scan();
        history = call(HttpMethod.GET, OPS + "/reminders?fleetId=" + fleet, admin, null, 200);
        assertThat(history).hasSize(4);
        assertThat(find(history, "CERTIFICATE").path("stage").asText()).isEqualTo("DUE_SOON");
        assertThat(find(history, "MAINTENANCE").path("cycleKey").asText()).isEqualTo("2");
    }

    @Test
    void repairRejectsSkippedStatesAndRequiresSettlementAmount() throws Exception {
        long id = id(call(HttpMethod.POST, CARE + "/vehicles/" + vehicle + "/repairs", admin,
                Map.of("description", "制动检查"), 200));
        String path = CARE + "/repairs/" + id + "/transition";
        call(HttpMethod.POST, path, admin, Map.of("status", "DONE"), 409);
        call(HttpMethod.POST, path, admin, Map.of("status", "ASSIGNED"), 400);
        call(HttpMethod.POST, path, admin, Map.of("status", "ASSIGNED", "assignee", "维修组"), 200);
        for (String state : List.of("IN_REPAIR", "DONE", "ACCEPTED")) {
            call(HttpMethod.POST, path, admin, Map.of("status", state, "shop", "测试维修厂"), 200);
        }
        call(HttpMethod.POST, path, admin, Map.of("status", "SETTLED"), 400);
        var terminal = call(HttpMethod.POST, path, admin, Map.of("status", "SETTLED", "cost", 108.55), 200);
        assertThat(terminal.path("cost").asDouble()).isEqualTo(108.55);
        assertThat(terminal.path("status").asText()).isEqualTo("SETTLED");
    }

    @Test
    void historicalFuelEditsRecomputeNeighboringIntervalsAndPeerStatus() throws Exception {
        long peer = vehicle("b");
        LocalDate start = today.minusDays(5);
        for (int i = 0; i < 4; i++) {
            fuel(peer, start.plusDays(i).atTime(8, 0), 10, 800 + 100 * i);
        }
        long previous = fuel(vehicle, start.atTime(9, 0), 15, 1000);
        fuel(vehicle, start.plusDays(1).atTime(9, 0), 20, 1100);
        String query = CARE + "/vehicles/" + vehicle + "/fuel?start=" + start.plusDays(1) + "&end=" + today;
        var view = call(HttpMethod.GET, query, admin, null, 200);
        assertThat(view.path("peerSamples").asInt()).isEqualTo(3);
        assertThat(view.path("peerAverage").asDouble()).isEqualTo(10);
        assertThat(view.path("rows").get(0).path("status").asText()).isEqualTo("ABNORMAL");
        assertThat(view.path("rows").get(0).path("record").path("litersPer100Km").asDouble()).isEqualTo(20);

        call(HttpMethod.PUT, CARE + "/fuel/" + previous, admin, fuelBody(start.atTime(9, 0), 15, 900), 200);
        view = call(HttpMethod.GET, query, admin, null, 200);
        assertThat(view.path("rows").get(0).path("status").asText()).isEqualTo("NORMAL");
        call(HttpMethod.PUT, CARE + "/fuel/" + previous, admin, fuelBody(start.atTime(9, 0), 15, 1100), 200);
        view = call(HttpMethod.GET, query, admin, null, 200);
        assertThat(view.path("rows").get(0).path("status").asText()).isEqualTo("ODOMETER_INVALID");
    }

    private JsonNode certificate(LocalDate expiry) throws Exception {
        return call(HttpMethod.PUT, OPS + "/certificates", admin, Map.of("subjectType", "VEHICLE", "subjectId", vehicle,
                "certificateType", "INSURANCE", "expiresOn", expiry), 200);
    }

    private static JsonNode find(JsonNode history, String source) {
        for (JsonNode row : history) {
            if (row.path("sourceType").asText().equals(source)) { return row; }
        }
        throw new AssertionError(source);
    }

    private long fuel(long id, LocalDateTime time, int liters, int km) throws Exception {
        return call(HttpMethod.POST, CARE + "/vehicles/" + id + "/fuel", admin, fuelBody(time, liters, km), 200).asLong();
    }

    private static Map<String, Object> fuelBody(LocalDateTime time, int liters, int km) {
        return Map.of("fueledAt", time, "liters", liters, "unitPrice", 7.25, "odometerKm", km);
    }

    private long user(String suffix, String role, Long binding) {
        String username = key + suffix;
        jdbc.update("INSERT INTO sys_user(username,password,name,fleet_id,role,vehicle_id) VALUES (?,'unused',?,?,?,?)",
                username, username, fleet, role, binding);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
    }

    private long vehicle(String suffix) {
        String plate = key + suffix;
        jdbc.update("INSERT INTO vehicle(fleet_id,plate_no,vehicle_type) VALUES (?,?,?)", fleet, plate, "货车");
        return jdbc.queryForObject("SELECT id FROM vehicle WHERE plate_no=?", Long.class, plate);
    }

    private JsonNode call(HttpMethod method, String path, long actor, Object body, int expected) throws Exception {
        var req = request(method, path).header("Authorization", "Bearer " + jwt.generateToken(actor, 0));
        if (body != null) { req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)); }
        var result = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse();
        return json.readTree(result.getContentAsByteArray()).path("data");
    }

    private int rawStatus(HttpMethod method, String path, long actor, Object body) throws Exception {
        return mvc.perform(request(method, path).header("Authorization", "Bearer " + jwt.generateToken(actor, 0))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andReturn().getResponse().getStatus();
    }

    private static long id(JsonNode value) { return value.path("id").asLong(); }
}
