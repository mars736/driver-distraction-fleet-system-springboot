package regression.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import service.recognition.RecognitionFileCleanup;
import service.report.ReportWorker;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class, properties = {
        "telemetry.offline-monitor.enabled=false", "report.worker.enabled=false", "recognition.cleanup.interval-ms=600000"})
@AutoConfigureMockMvc
class BatchTwoIntegrationTest {
    @TempDir static Path storage;

    @DynamicPropertySource
    static void testStorage(DynamicPropertyRegistry registry) {
        registry.add("file.upload-dir", () -> storage.toString());
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @Autowired ReportWorker worker;
    @Autowired RecognitionFileCleanup cleanup;

    private long fleet;
    private long vehicle;
    private long driver;
    private long admin;
    private long otherAdmin;

    @BeforeEach
    void createFixtures() {
        String key = "b2-" + UUID.randomUUID().toString().substring(0, 10);
        jdbc.update("INSERT INTO fleet(fleet_no, name) VALUES (?, ?)", key, "报表测试车队");
        fleet = jdbc.queryForObject("SELECT id FROM fleet WHERE fleet_no=?", Long.class, key);
        jdbc.update("INSERT INTO vehicle(fleet_id, plate_no) VALUES (?, ?)", fleet, key);
        vehicle = jdbc.queryForObject("SELECT id FROM vehicle WHERE plate_no=?", Long.class, key);
        driver = user(key + "-driver", "DRIVER", "=司机", vehicle);
        admin = user(key + "-admin", "PLATFORM_ADMIN", "管理员", null);
        otherAdmin = user(key + "-other", "PLATFORM_ADMIN", "另一位管理员", null);
    }

    @AfterEach
    void removeFixtures() throws Exception {
        jdbc.update("DELETE FROM report_job WHERE owner_id IN (?,?)", admin, otherAdmin);
        jdbc.update("DELETE FROM operation_log WHERE actor_id IN (?,?) OR fleet_id=?", admin, otherAdmin, fleet);
        jdbc.update("DELETE FROM alert_appeal WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM alert_record WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM recognition_record WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM vehicle_telemetry WHERE vehicle_id=?", vehicle);
        jdbc.update("DELETE FROM sys_user WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM vehicle WHERE id=?", vehicle);
        jdbc.update("DELETE FROM fleet WHERE id=?", fleet);
    }

    @Test
    void asyncExcelUsesTypedCellsOwnerOnlyAndExpires() throws Exception {
        long recognition = recognition(null);
        alert(recognition, null);
        long id = createReport("""
                {"type":"ALERT","format":"XLSX","fleetId":%d,"driverId":%d,"recordType":"SMS_ALERT"}
                """.formatted(fleet, driver));
        mvc.perform(get("/api/report-jobs/{id}/file", id).header("Authorization", token(admin)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/report-jobs/{id}", id).header("Authorization", token(otherAdmin)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/report-jobs").header("Authorization", token(driver))).andExpect(status().isForbidden());
        worker.runOnce();
        mvc.perform(get("/api/report-jobs/{id}", id).header("Authorization", token(admin)))
                .andExpect(jsonPath("$.data.status").value("COMPLETED")).andExpect(jsonPath("$.data.rowCount").value(1));
        byte[] bytes = download(id, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row.getCell(1).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 11, 20, 10, 0));
            assertThat(row.getCell(7).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(7).getStringCellValue()).isEqualTo("=司机");
        }
        String run = jdbc.queryForObject("SELECT run_token FROM report_job WHERE id=?", String.class, id);
        mvc.perform(get("/upload/reports/" + run + ".xlsx").header("Authorization", token(admin)))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE report_job SET expires_at='2020-01-01 00:00:00' WHERE id=?", id);
        mvc.perform(get("/api/report-jobs/{id}/file", id).header("Authorization", token(admin)))
                .andExpect(status().isGone());
        worker.runOnce();
        assertThat(Files.exists(storage.resolve("reports").resolve(run + ".xlsx"))).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM report_job WHERE id=?", String.class, id)).isEqualTo("EXPIRED");
    }

    @Test
    void reportsRecoverAndReuseRecognitionScoreAndDashboardData() throws Exception {
        recognition(null);
        String month = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();
        long recognitionReport = createReport("""
                {"type":"RECOGNITION","format":"XLSX","fleetId":%d,"driverId":%d,"behaviorCode":"c1"}
                """.formatted(fleet, driver));
        long score = createReport("""
                {"type":"DRIVER_SCORE","format":"XLSX","fleetId":%d,"driverId":%d,"month":"%s"}
                """.formatted(fleet, driver, month));
        long dashboard = createReport("""
                {"type":"FLEET_STATISTICS","format":"XLSX","fleetId":%d,"startTime":"2026-11-20T00:00:00","endTime":"2026-11-21T00:00:00"}
                """.formatted(fleet));
        jdbc.update("UPDATE report_job SET status='RUNNING', attempts=1, run_token=?, started_at='2020-01-01' WHERE id=?",
                UUID.randomUUID().toString(), recognitionReport);
        for (int i = 0; i < 3; i++) { worker.runOnce(); }
        assertThat(jdbc.queryForObject("SELECT attempts FROM report_job WHERE id=?", Integer.class, recognitionReport)).isEqualTo(2);
        for (long id : new long[]{recognitionReport, score, dashboard}) {
            mvc.perform(get("/api/report-jobs/{id}", id).header("Authorization", token(admin)))
                    .andExpect(jsonPath("$.data.status").value("COMPLETED")).andExpect(jsonPath("$.data.rowCount").value(1));
        }
        mvc.perform(post("/api/report-jobs").header("Authorization", token(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DRIVER_SCORE\",\"format\":\"PDF\",\"vehicleId\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pdfHasChineseHeadersEmptyResultAndRepeatedHeaderOnNextPage() throws Exception {
        long id = createReport("""
                {"type":"RECOGNITION","format":"PDF","fleetId":%d,"driverId":%d}
                """.formatted(fleet, driver));
        worker.runOnce();
        var reader = new PdfReader(download(id, "application/pdf"));
        try {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
            var text = new PdfTextExtractor(reader).getTextFromPage(1).replaceAll("\\s+", "");
            assertThat(text).contains("识别记录报表", "无匹配记录");
        } finally { reader.close(); }

        for (int i = 0; i < 120; i++) { recognition(null); }
        long full = createReport("""
                {"type":"RECOGNITION","format":"PDF","fleetId":%d}
                """.formatted(fleet));
        worker.runOnce();
        var multipage = new PdfReader(download(full, "application/pdf"));
        try {
            assertThat(multipage.getNumberOfPages()).isGreaterThan(1);
            assertThat(new PdfTextExtractor(multipage).getTextFromPage(2).replaceAll("\\s+", ""))
                    .contains("识别ID", "采集时间");
        } finally { multipage.close(); }
    }

    @Test
    void onlyUnreferencedRowsAreDeletedAndSharedFramesAreKept() throws Exception {
        String key = "alerts/2026/11/20/" + UUID.randomUUID() + ".jpg";
        Path file = storage.resolve(key);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});
        long first = recognition(key);
        long second = recognition(key);
        long linked = recognition(null);
        alert(linked, null);
        mvc.perform(delete("/api/recognitions/{id}", first).header("Authorization", token(driver)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/recognitions/batch-delete").header("Authorization", token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[" + first + "," + linked + "]}"))
                .andExpect(status().isConflict());
        assertThat(Files.exists(file)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recognition_record WHERE id=?", Integer.class, first)).isEqualTo(1);
        mvc.perform(delete("/api/recognitions/{id}", first).header("Authorization", token(admin)))
                .andExpect(jsonPath("$.data.deleted").value(1));
        cleanup.run();
        assertThat(Files.exists(file)).isTrue();
        mvc.perform(delete("/api/recognitions/{id}", second).header("Authorization", token(admin)))
                .andExpect(jsonPath("$.data.cleanupQueued").value(1));
        cleanup.run();
        assertThat(Files.exists(file)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_log WHERE actor_id=? AND action='RECOGNITION_DELETE'",
                Integer.class, admin)).isEqualTo(2);
    }

    @Test
    void cleanupDoesNotDeleteFilesOutsideTheManagedFrameDirectory() throws Exception {
        Path otherFile = storage.resolve("appeals").resolve(UUID.randomUUID() + ".jpg");
        Files.createDirectories(otherFile.getParent());
        Files.write(otherFile, new byte[]{1, 2, 3});
        long record = recognition("alerts/../appeals/" + otherFile.getFileName());
        mvc.perform(delete("/api/recognitions/{id}", record).header("Authorization", token(admin)))
                .andExpect(status().isOk());
        cleanup.run();
        assertThat(otherFile).exists();
    }

    @Test
    void aClosedAppealStillProtectsItsSourceRecord() throws Exception {
        long id = recognition(null);
        jdbc.update("""
                INSERT INTO alert_appeal(target_type,target_id,driver_id,fleet_id,vehicle_id,reason,target_status_before,status,create_time,update_time)
                VALUES ('RECOGNITION',?,?,?,?,'测试','EVALUATED','WITHDRAWN',NOW(),NOW())
                """, id, driver, fleet, vehicle);
        mvc.perform(delete("/api/recognitions/{id}", id).header("Authorization", token(admin)))
                .andExpect(status().isConflict());
    }

    @Test
    void trackBreaksOnGapsAndJumpsAndKeepsDistanceAcrossPages() throws Exception {
        long base = 1_700_000_000_000L;
        position(base, 118.0);
        position(base + 5000, 118.001);
        position(base + 45_000, 118.002);
        position(base + 50_000, 118.003);
        position(base + 55_000, 119.000);
        position(base + 60_000, 119.001);

        var track = get("/api/vehicles/{id}/track", vehicle).param("startTime", Long.toString(base))
                .param("endTime", Long.toString(base + 60_000)).param("pageSize", "3");
        mvc.perform(track.header("Authorization", token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalPoints").value(5))
                .andExpect(jsonPath("$.data.segmentCount").value(3))
                .andExpect(jsonPath("$.data.totalDistanceKm").value(closeTo(0.22239, 0.0001), Double.class));
        mvc.perform(get("/api/vehicles/{id}/track", vehicle).param("startTime", Long.toString(base))
                        .param("endTime", Long.toString(base + 60_000)).param("page", "2").param("pageSize", "3")
                        .header("Authorization", token(admin)))
                .andExpect(jsonPath("$.data.points[0].distanceFromPreviousKm").value(closeTo(0.111195, 0.0001), Double.class))
                .andExpect(jsonPath("$.data.points[1].segment").value(3));
        mvc.perform(get("/api/vehicles/{id}/track", vehicle).param("startTime", Long.toString(base))
                        .param("endTime", Long.toString(base + 5000)).header("Authorization", token(driver)))
                .andExpect(status().isForbidden());
    }

    private void position(long time, double longitude) {
        jdbc.update("INSERT INTO vehicle_telemetry VALUES (?,?,?,0,?,30)", vehicle, driver, time, longitude);
    }

    private long user(String username, String role, String name, Long assignedVehicle) {
        jdbc.update("INSERT INTO sys_user(username,password,name,role,fleet_id,vehicle_id) VALUES (?,'unused',?,?,?,?)",
                username, name, role, fleet, assignedVehicle);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
    }

    private long recognition(String path) {
        String source = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO recognition_record(source_key,source_frame_id,vehicle_id,driver_id,fleet_id,captured_at,
                    pred,behavior_code,max_p,ood,participated,decision_reason,speed_status,key_frame_path)
                VALUES (?,1,?,?,?,'2026-11-20 10:00:00',1,'c1',0.95,0,1,'EVALUATED','KNOWN',?)
                """, source, vehicle, driver, fleet, path);
        return jdbc.queryForObject("SELECT id FROM recognition_record WHERE source_key=?", Long.class, source);
    }

    private void alert(long recognition, String path) {
        jdbc.update("""
                INSERT INTO alert_record(recognition_id,vehicle_id,driver_id,fleet_id,captured_at,record_type,sms_status,key_frame_path)
                VALUES (?,?,?,?,'2026-11-20 10:00:00','SMS_ALERT','SIMULATED',?)
                """, recognition, vehicle, driver, fleet, path);
    }

    private long createReport(String data) throws Exception {
        var response = mvc.perform(post("/api/report-jobs").header("Authorization", token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(data))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PENDING")).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray()).path("data").path("id").asLong();
    }

    private byte[] download(long id, String mediaType) throws Exception {
        return mvc.perform(get("/api/report-jobs/{id}/file", id).header("Authorization", token(admin)))
                .andExpect(status().isOk()).andExpect(content().contentType(mediaType)).andReturn().getResponse().getContentAsByteArray();
    }

    private String token(long id) { return "Bearer " + jwt.generateToken(id, 0); }
}
