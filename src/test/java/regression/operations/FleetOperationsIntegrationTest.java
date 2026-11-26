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
import java.util.concurrent.Callable;
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
    void cancellationOnlySucceedsBeforeDepartureAndNeverCreatesAttendance() throws Exception {
        prepareOnDuty();
        var body = taskRequest(now.minusMinutes(2), now.plusHours(1));
        long pending = id(call(HttpMethod.POST, WORK + "/tasks", admin, body, 200));
        String pendingPath = WORK + "/tasks/" + pending;
        assertThat(call(HttpMethod.POST, pendingPath + "/cancel", admin, null, 200).path("status").asText())
                .isEqualTo("CANCELED");
        var totals = call(HttpMethod.GET, WORK + "/attendance/summary?start=" + today.minusDays(1)
                + "&end=" + today.plusDays(1), driver, null, 200);
        assertThat(totals.path("taskDays").asInt()).isZero();

        var point = Map.of("latitude", 24.48, "longitude", 118.08);
        long next = id(call(HttpMethod.POST, WORK + "/tasks", admin, body, 200));
        String path = WORK + "/tasks/" + next;
        race(() -> rawStatus(HttpMethod.POST, path + "/departure", driver, point),
                () -> rawStatus(HttpMethod.POST, path + "/cancel", admin, null));
        var result = call(HttpMethod.GET, path, admin, null, 200);
        String state = result.path("status").asText();
        assertThat(state).isIn("RUNNING", "CANCELED");
        assertThat(result.path("departedAt").isTextual()).isEqualTo(state.equals("RUNNING"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_log WHERE action='TASK_CANCEL' AND target_id=?",
                Integer.class, next)).isEqualTo(state.equals("CANCELED") ? 1 : 0);
        assertThat(call(HttpMethod.POST, path + "/cancel", admin, null, 409).path("reason").asText())
                .isEqualTo("TASK_NOT_PENDING");

        if (state.equals("RUNNING")) { call(HttpMethod.POST, path + "/completion", driver, point, 200); }
        long confirmed = id(call(HttpMethod.POST, WORK + "/tasks", admin, body, 200));
        String confirmedPath = WORK + "/tasks/" + confirmed;
        call(HttpMethod.POST, confirmedPath + "/departure", driver, point, 200);
        call(HttpMethod.POST, confirmedPath + "/cancel", admin, null, 409);
        call(HttpMethod.POST, confirmedPath + "/completion", driver, point, 200);
        call(HttpMethod.POST, confirmedPath + "/cancel", admin, null, 409);
    }

    @Test
    void restAndDispatchRejectEachOtherAndShareTheDriverLock() throws Exception {
        prepareOnDuty();
        var rest = today.plusDays(2);
        call(HttpMethod.PUT, WORK + "/shifts", admin, restRequest(rest), 200);
        var overlaps = taskRequest(rest.atStartOfDay().minusHours(1), rest.atStartOfDay().plusMinutes(1));
        assertThat(call(HttpMethod.POST, WORK + "/tasks", admin, overlaps, 409).path("reason").asText())
                .isEqualTo("TASK_OVERLAPS_REST");
        call(HttpMethod.POST, WORK + "/tasks", admin,
                taskRequest(rest.atStartOfDay().minusHours(1), rest.atStartOfDay()), 200);

        var pending = today.plusDays(3);
        call(HttpMethod.POST, WORK + "/tasks", admin, taskRequest(pending.atTime(8, 0), pending.atTime(10, 0)), 200);
        assertThat(call(HttpMethod.PUT, WORK + "/shifts", admin, restRequest(pending), 409).path("reason").asText())
                .isEqualTo("REST_OVERLAPS_TASK");

        var concurrent = today.plusDays(4);
        race(() -> rawStatus(HttpMethod.PUT, WORK + "/shifts", admin, restRequest(concurrent)),
                () -> rawStatus(HttpMethod.POST, WORK + "/tasks", admin,
                        taskRequest(concurrent.atTime(8, 0), concurrent.atTime(10, 0))));
    }

    @Test
    void departureRechecksRestAndOverdueRunningTaskBlocksRestAndLeave() throws Exception {
        prepareOnDuty();
        var current = id(call(HttpMethod.POST, WORK + "/tasks", admin,
                taskRequest(now.minusMinutes(2), now.plusDays(1).plusMinutes(1)), 200));
        var tomorrow = today.plusDays(1);
        jdbc.update("""
                INSERT INTO driver_shift(driver_id,fleet_id,shift_date,shift_type)
                VALUES(?,?,?,'REST')
                """, driver, fleet, tomorrow);
        var point = Map.of("latitude", 24.48, "longitude", 118.08);
        assertThat(call(HttpMethod.POST, WORK + "/tasks/" + current + "/departure", driver, point, 409)
                .path("reason").asText()).isEqualTo("TASK_OVERLAPS_REST");
        var departed = now.minusDays(3);
        jdbc.update("""
                UPDATE transport_task SET status='RUNNING', starts_at=?, ends_at=?, departed_at=? WHERE id=?
                """, departed.minusHours(1), departed.plusHours(1), departed, current);
        assertThat(call(HttpMethod.PUT, WORK + "/shifts", admin, restRequest(today.minusDays(1)), 409)
                .path("reason").asText()).isEqualTo("REST_OVERLAPS_TASK");
        var leave = requestLeave(today.minusDays(1).atStartOfDay(), now.plusMinutes(30));
        assertThat(call(HttpMethod.POST, WORK + "/leaves/" + leave + "/decision", admin, Map.of("approved", true), 409)
                .path("reason").asText()).isEqualTo("LEAVE_OVERLAPS_SHIFT");
        // 次日以前的额外实际执行时间也纳入任务冲突，不受原计划结束时间限制。
        long oldLeave = requestLeave(today.minusDays(2).atStartOfDay(), now.plusMinutes(30));
        jdbc.update("DELETE FROM driver_attendance WHERE shift_id IN (SELECT id FROM driver_shift WHERE driver_id=?)", driver);
        jdbc.update("DELETE FROM driver_shift WHERE driver_id=? AND shift_type<>'REST'", driver);
        assertThat(call(HttpMethod.POST, WORK + "/leaves/" + oldLeave + "/decision", admin, Map.of("approved", true), 409)
                .path("reason").asText()).isEqualTo("LEAVE_OVERLAPS_TASK");
    }

    @Test
    void overnightWorkAndApprovedLeaveUseHalfOpenRangesInBothDirections() throws Exception {
        var night = today.plusDays(2);
        var boundary = night.plusDays(1).atTime(4, 0);
        call(HttpMethod.PUT, WORK + "/shifts", admin,
                workShift(night.atTime(20, 0), boundary), 200);
        long overlaps = requestLeave(boundary.minusMinutes(1), boundary.plusHours(1));
        long touches = requestLeave(boundary, boundary.plusHours(1));
        var approve = Map.of("approved", true);
        assertThat(call(HttpMethod.POST, WORK + "/leaves/" + overlaps + "/decision", admin, approve, 409)
                .path("reason").asText()).isEqualTo("LEAVE_OVERLAPS_SHIFT");
        call(HttpMethod.POST, WORK + "/leaves/" + touches + "/decision", admin, approve, 200);
        assertThat(call(HttpMethod.PUT, WORK + "/shifts", admin, workShift(boundary, boundary.plusHours(2)), 409)
                .path("reason").asText()).isEqualTo("SHIFT_OVERLAPS_LEAVE");

        var concurrent = today.plusDays(5).atTime(8, 0);
        long leave = requestLeave(concurrent, concurrent.plusHours(2));
        race(() -> rawStatus(HttpMethod.POST, WORK + "/leaves/" + leave + "/decision", admin, approve),
                () -> rawStatus(HttpMethod.PUT, WORK + "/shifts", admin, workShift(concurrent, concurrent.plusHours(3))));
    }

    @Test
    void stoppedFleetRejectsNewDispatchAndDepartureButAllowsCompletion() throws Exception {
        prepareOnDuty();
        var body = taskRequest(now.minusMinutes(1), now.plusHours(1));
        long task = id(call(HttpMethod.POST, WORK + "/tasks", admin, body, 200));
        String path = WORK + "/tasks/" + task;
        String fleetPath = "/api/fleets/" + fleet + "/enabled?enabled=";
        var point = Map.of("latitude", 24.48, "longitude", 118.08);
        assertThat(call(HttpMethod.GET, WORK + "/on-duty?fleetId=" + fleet, admin, null, 200)).hasSize(1);
        call(HttpMethod.PATCH, fleetPath + "false", admin, null, 200);
        assertThat(call(HttpMethod.GET, WORK + "/on-duty?fleetId=" + fleet, admin, null, 200)).isEmpty();
        assertThat(call(HttpMethod.POST, WORK + "/tasks", admin, body, 409).path("reason").asText())
                .isEqualTo("FLEET_STOPPED");
        assertThat(call(HttpMethod.POST, path + "/departure", driver, point, 409).path("reason").asText())
                .isEqualTo("FLEET_STOPPED");
        call(HttpMethod.PATCH, fleetPath + "true", admin, null, 200);
        call(HttpMethod.POST, path + "/departure", driver, point, 200);
        call(HttpMethod.PATCH, fleetPath + "false", admin, null, 200);
        assertThat(call(HttpMethod.POST, path + "/completion", driver, point, 200).path("status").asText())
                .isEqualTo("COMPLETED");
    }

    @Test
    void dispatchRetryKeepsOneAuditAndCursorAndDetailsEnforceDriverScope() throws Exception {
        prepareOnDuty();
        var at = now.plusDays(3).withNano(123456789);
        var body = taskRequest(at, at.plusHours(2));
        String requestKey = UUID.randomUUID().toString();
        long first;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> createWithKey(body, requestKey, 200));
            var b = pool.submit(() -> createWithKey(body, requestKey, 200));
            first = id(a.get(10, TimeUnit.SECONDS));
            assertThat(id(b.get(10, TimeUnit.SECONDS))).isEqualTo(first);
        }
        assertThat(id(createWithKey(body, requestKey, 200))).isEqualTo(first);
        assertThat(createWithKey(taskRequest(at, at.plusHours(3)), requestKey, 409).path("reason").asText())
                .isEqualTo("TASK_REQUEST_KEY_REUSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_log WHERE fleet_id=? AND action='TASK_CREATE'",
                Integer.class, fleet)).isEqualTo(1);

        call(HttpMethod.POST, WORK + "/tasks", admin, taskRequest(at.plusDays(1), at.plusDays(1).plusHours(1)), 200);
        String tasks = WORK + "/tasks/page?pageSize=1&status=PENDING";
        var page = call(HttpMethod.GET, tasks, driver, null, 200);
        assertThat(page.path("hasNext").asBoolean()).isTrue();
        call(HttpMethod.POST, WORK + "/tasks", admin, taskRequest(at.plusDays(2), at.plusDays(2).plusHours(1)), 200);
        var next = call(HttpMethod.GET, tasks + "&beforeId=" + page.path("nextCursor").asLong(), driver, null, 200);
        assertThat(id(next.path("records").get(0))).isEqualTo(first);
        assertThat(next.path("hasNext").asBoolean()).isFalse();
        call(HttpMethod.GET, tasks + "&beforeId=0", driver, null, 400);
        call(HttpMethod.GET, WORK + "/tasks/" + first, driver, null, 200);
        call(HttpMethod.GET, WORK + "/tasks/" + first, otherDriver, null, 404);

        long olderLeave = requestLeave(at.plusDays(6), at.plusDays(6).plusHours(1));
        long newerLeave = requestLeave(at.plusDays(7), at.plusDays(7).plusHours(1));
        String leaves = WORK + "/leaves/page?pageSize=1";
        var leavePage = call(HttpMethod.GET, leaves, driver, null, 200);
        assertThat(id(leavePage.path("records").get(0))).isEqualTo(newerLeave);
        assertThat(id(call(HttpMethod.GET, leaves + "&beforeId=" + leavePage.path("nextCursor").asLong(),
                driver, null, 200).path("records").get(0))).isEqualTo(olderLeave);
        assertThat(call(HttpMethod.GET, leaves, otherDriver, null, 200).path("records")).isEmpty();
        call(HttpMethod.GET, WORK + "/leaves/" + olderLeave, admin, null, 200);
        call(HttpMethod.GET, WORK + "/leaves/" + olderLeave, otherDriver, null, 404);
    }

    @Test
    void attendanceCountsActualDaysAndApprovedLeaveSeparatelyWithExclusiveEnd() throws Exception {
        var day = today.minusDays(4);
        var end = day.plusDays(1);
        for (var date : List.of(day, end)) {
            long shift = id(call(HttpMethod.PUT, WORK + "/shifts", admin,
                    workShift(date.atTime(8, 0), date.atTime(16, 0)), 200));
            jdbc.update("""
                    INSERT INTO driver_attendance(shift_id,clock_in_at,clock_in_lat,clock_in_lon)
                    VALUES(?,?,24.48,118.08)
                    """, shift, date.atTime(8, 0));
        }
        jdbc.update("""
                INSERT INTO transport_task(driver_id,vehicle_id,fleet_id,task_type,description,starts_at,ends_at,status,
                    created_by,created_at,departed_at,ended_at)
                VALUES(?,?,?,'测试','跨午夜边界',?,?,'COMPLETED',?,?,?,?)
                """, driver, vehicle, fleet, day.atTime(23, 30), end.atStartOfDay(), admin, day.atTime(17, 0),
                day.atTime(23, 30), end.atStartOfDay());
        jdbc.update("""
                INSERT INTO driver_leave(driver_id,fleet_id,leave_type,starts_at,ends_at,reason,status,created_at)
                VALUES(?,?,'PERSONAL',?,?,'历史请假','APPROVED',?)
                """, driver, fleet, day.atTime(17, 0), day.atTime(18, 0), day.atStartOfDay());
        var totals = call(HttpMethod.GET, WORK + "/attendance/summary?start=" + day + "&end=" + end, driver, null, 200);
        assertThat(totals.path("taskDays").asInt()).isEqualTo(1);
        assertThat(totals.path("punchDays").asInt()).isEqualTo(1);
        assertThat(totals.path("attendedDays").asInt()).isEqualTo(1);
        assertThat(totals.path("leaveDays").asInt()).isEqualTo(1);
        assertThat(totals.path("attendedDates").get(0).asText()).isEqualTo(day.toString());
        var following = call(HttpMethod.GET, WORK + "/attendance/summary?start=" + end + "&end=" + end.plusDays(1),
                driver, null, 200);
        assertThat(following.path("taskDays").asInt()).isZero();
        assertThat(following.path("leaveDays").asInt()).isZero();
    }

    private void prepareOnDuty() throws Exception {
        var starts = now.minusMinutes(20);
        call(HttpMethod.PUT, WORK + "/shifts", admin, workShift(starts, now.plusHours(2)), 200);
        call(HttpMethod.POST, WORK + "/attendance/in", driver, Map.of("shiftDate", starts.toLocalDate(),
                "position", Map.of("latitude", 24.48, "longitude", 118.08)), 200);
    }

    private Map<String, Object> taskRequest(LocalDateTime starts, LocalDateTime ends) {
        return Map.of("driverId", driver, "vehicleId", vehicle, "type", "运输", "description", "派单校验",
                "startsAt", starts, "endsAt", ends);
    }

    private Map<String, Object> workShift(LocalDateTime starts, LocalDateTime ends) {
        return Map.of("driverId", driver, "date", starts.toLocalDate(), "type", "LATE",
                "startsAt", starts, "endsAt", ends);
    }

    private Map<String, Object> restRequest(LocalDate date) {
        return Map.of("driverId", driver, "date", date, "type", "REST");
    }

    private long requestLeave(LocalDateTime starts, LocalDateTime ends) throws Exception {
        return id(call(HttpMethod.POST, WORK + "/leaves", driver,
                Map.of("type", "PERSONAL", "startsAt", starts, "endsAt", ends, "reason", "请假校验"), 200));
    }

    private JsonNode createWithKey(Object body, String requestKey, int expected) throws Exception {
        var req = request(HttpMethod.POST, WORK + "/tasks").header("Authorization", "Bearer " + jwt.generateToken(admin, 0))
                .header("Idempotency-Key", requestKey).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        var response = mvc.perform(req).andReturn().getResponse();
        assertThat(response.getStatus())
                .as("派单响应 %s", response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(expected);
        return json.readTree(response.getContentAsByteArray()).path("data");
    }

    private void race(Callable<Integer> first, Callable<Integer> second) throws Exception {
        var ready = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { ready.await(); return first.call(); });
            var b = pool.submit(() -> { ready.await(); return second.call(); });
            ready.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
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
                        .contentType(MediaType.APPLICATION_JSON).content(body == null ? new byte[0] : json.writeValueAsBytes(body)))
                .andReturn().getResponse().getStatus();
    }

    private static long id(JsonNode value) { return value.path("id").asLong(); }
}
