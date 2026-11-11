package regression.alert;

import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class)
@AutoConfigureMockMvc
class AlertQueryIntegrationTest {
    @TempDir static Path uploads;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("file.upload-dir", () -> uploads.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    private final List<Long> alerts = new ArrayList<>();
    private final List<Long> recognitions = new ArrayList<>();
    private final List<Long> users = new ArrayList<>();
    private final List<Long> vehicles = new ArrayList<>();
    private final List<Long> fleets = new ArrayList<>();
    private long fleetA, fleetB, vehicleA, vehicleB, driverA, driverB;
    private long simulated, otherDriver, unknown, sent, backlog, ownerless;
    private String adminToken, driverToken, relativeImage;
    private byte[] image;

    @BeforeEach
    void fixtures() throws Exception {
        String key = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        fleetA = insert("INSERT INTO fleet(fleet_no,name) VALUES (?,?)", "query-A-" + key, "查询测试车队A"); fleets.add(fleetA);
        fleetB = insert("INSERT INTO fleet(fleet_no,name) VALUES (?,?)", "query-B-" + key, "查询测试车队B"); fleets.add(fleetB);
        vehicleA = insert("INSERT INTO vehicle(fleet_id,plate_no) VALUES (?,?)", fleetA, "QA" + key.substring(0, 10)); vehicles.add(vehicleA);
        vehicleB = insert("INSERT INTO vehicle(fleet_id,plate_no) VALUES (?,?)", fleetB, "QB" + key.substring(0, 10)); vehicles.add(vehicleB);
        driverA = user("query-A-" + key, "DRIVER", fleetA, vehicleA);
        driverB = user("query-B-" + key, "DRIVER", fleetB, vehicleB);
        long admin = user("query-admin-" + key, "PLATFORM_ADMIN", null, null);
        adminToken = "Bearer " + jwt.generateToken(admin, 0);
        driverToken = "Bearer " + jwt.generateToken(driverA, 0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpg", bytes);
        image = bytes.toByteArray();
        relativeImage = "alerts/query-" + key + "/trigger.jpg";
        Files.createDirectories(uploads.resolve(relativeImage).getParent());
        Files.write(uploads.resolve(relativeImage), image);
        Files.writeString(uploads.resolve("public-test.txt"), "public test fixture");
        simulated = alert(vehicleA, driverA, fleetA, "SMS_ALERT", "SIMULATED", "c1", "10:00:00", relativeImage);
        otherDriver = alert(vehicleB, driverB, fleetB, "BACKLOG", "NOT_REQUIRED", "c2", "10:30:00", null);
        unknown = alert(vehicleA, driverA, fleetA, "SPEED_UNKNOWN", "NOT_REQUIRED", null, "10:45:00", null);
        sent = alert(vehicleA, driverA, fleetA, "SMS_ALERT", "SENT", "c3", "11:00:00", null);
        backlog = alert(vehicleA, driverA, fleetA, "BACKLOG", "NOT_REQUIRED", "c4", "10:30:00", null);
        ownerless = alert(vehicleA, null, fleetA, "SPEED_UNKNOWN", "NOT_REQUIRED", null, "10:50:00", null);
    }

    @AfterEach
    void cleanup() throws Exception {
        for (Long id : alerts) { jdbc.update("DELETE FROM alert_record WHERE id=?", id); }
        for (Long id : recognitions) { jdbc.update("DELETE FROM recognition_record WHERE id=?", id); }
        for (Long id : users) { jdbc.update("DELETE FROM sys_user WHERE id=?", id); }
        for (Long id : vehicles) { jdbc.update("DELETE FROM vehicle WHERE id=?", id); }
        for (Long id : fleets) { jdbc.update("DELETE FROM fleet WHERE id=?", id); }
        if (relativeImage != null) { Files.deleteIfExists(uploads.resolve(relativeImage)); }
        Files.deleteIfExists(uploads.resolve("public-test.txt"));
    }

    @Test
    void administratorCanFilterAndPageWithStableOrdering() throws Exception {
        mvc.perform(get("/api/alerts").header("Authorization", adminToken)
                        .param("vehicleId", "" + vehicleA).param("page", "2").param("pageSize", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(5)).andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(2))
                .andExpect(jsonPath("$.data.list[*].id").value(contains((int) unknown, (int) backlog)));
        mvc.perform(get("/api/alerts").header("Authorization", adminToken)
                        .param("fleetId", "" + fleetA).param("vehicleId", "" + vehicleA)
                        .param("driverId", "" + driverA).param("behaviorCode", "c1").param("recordType", "SMS_ALERT")
                        .param("startTime", "2026-11-11T10:00:00").param("endTime", "2026-11-11T11:00:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(simulated));
        mvc.perform(get("/api/alerts").header("Authorization", adminToken).param("fleetId", "" + fleetB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(otherDriver));
    }

    @Test
    void driverScopeCannotBeOverriddenByRequestParameters() throws Exception {
        mvc.perform(get("/api/alerts").header("Authorization", driverToken).param("ownerDriverId", "" + driverB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.list[*].driverId", everyItem(is((int) driverA))));
        mvc.perform(get("/api/alerts").header("Authorization", driverToken).param("driverId", "" + driverB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.list").isEmpty());
        mvc.perform(get("/api/alerts").header("Authorization", driverToken).param("vehicleId", "" + vehicleB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void driverDetailIsOwnOnlyAndUsesProtectedImageUrl() throws Exception {
        mvc.perform(get("/api/alerts/" + simulated).header("Authorization", driverToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(simulated))
                .andExpect(jsonPath("$.data.driverName").isNotEmpty())
                .andExpect(jsonPath("$.data.plateNo").isNotEmpty())
                .andExpect(jsonPath("$.data.fleetName").value("查询测试车队A"))
                .andExpect(jsonPath("$.data.smsStatus").value("SIMULATED"))
                .andExpect(jsonPath("$.data.smsSimulated").value(true))
                .andExpect(jsonPath("$.data.violation").value(true))
                .andExpect(jsonPath("$.data.keyFramePath").doesNotExist())
                .andExpect(jsonPath("$.data.keyFrameUrl").value("/api/alerts/" + simulated + "/key-frame"));
        for (long id : new long[]{otherDriver, ownerless, Long.MAX_VALUE}) {
            mvc.perform(get("/api/alerts/" + id).header("Authorization", driverToken))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        }
        mvc.perform(get("/api/alerts/" + otherDriver).header("Authorization", adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void unknownSpeedIsNotViolationAndSentIsNotSimulation() throws Exception {
        mvc.perform(get("/api/alerts/" + unknown).header("Authorization", driverToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.violation").value(false))
                .andExpect(jsonPath("$.data.speedStatus").value("UNKNOWN"));
        mvc.perform(get("/api/alerts/" + sent).header("Authorization", driverToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.smsStatus").value("SENT"))
                .andExpect(jsonPath("$.data.smsSimulated").value(false));
    }

    @Test
    void imageRequiresRecordPermissionAndDisablesCaching() throws Exception {
        mvc.perform(get("/api/alerts/" + simulated + "/key-frame").header("Authorization", driverToken))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(image)).andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get("/api/alerts/" + otherDriver + "/key-frame").header("Authorization", driverToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        mvc.perform(get("/api/alerts/" + sent + "/key-frame").header("Authorization", driverToken))
                .andExpect(status().isNotFound());
        mvc.perform(head("/api/alerts/" + simulated + "/key-frame").header("Authorization", driverToken))
                .andExpect(status().isOk()).andExpect(content().string(""));
    }

    @Test
    void directStaticImageAccessIsClosedIncludingWindowsCaseVariants() throws Exception {
        mvc.perform(get("/upload/public-test.txt").header("Authorization", driverToken))
                .andExpect(status().isOk()).andExpect(content().string("public test fixture"));
        mvc.perform(get("/upload/" + relativeImage).header("Authorization", driverToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/upload/" + relativeImage.replace("alerts/", "ALERTS/")).header("Authorization", driverToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void databaseImagePathCannotEscapeUploadRoot() throws Exception {
        jdbc.update("UPDATE alert_record SET key_frame_path='../outside.jpg' WHERE id=?", simulated);
        mvc.perform(get("/api/alerts/" + simulated + "/key-frame").header("Authorization", driverToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        jdbc.update("UPDATE alert_record SET key_frame_path=? WHERE id=?", uploads.getParent().resolve("outside.jpg").toString(), simulated);
        mvc.perform(get("/api/alerts/" + simulated + "/key-frame").header("Authorization", driverToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void timeRangeUsesInclusiveStartAndExclusiveEnd() throws Exception {
        mvc.perform(get("/api/alerts").header("Authorization", driverToken)
                        .param("startTime", "2026-11-11T10:00:00").param("endTime", "2026-11-11T11:00:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
    }

    @ParameterizedTest
    @CsvSource({"page,0", "pageSize,101", "vehicleId,-1", "driverId,abc", "recordType,INVALID",
            "behaviorCode,c99", "startTime,2026-02-30T10:00:00", "startTime,2026-11-11T10:00:00Z"})
    void invalidQueriesReturnResult400(String name, String value) throws Exception {
        mvc.perform(get("/api/alerts").header("Authorization", adminToken).param(name, value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void reversedRangeIsRejectedAndLargePageDoesNotOverflow() throws Exception {
        mvc.perform(get("/api/alerts").header("Authorization", driverToken)
                        .param("startTime", "2026-11-11T11:00:00").param("endTime", "2026-11-11T10:00:00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/alerts").header("Authorization", driverToken).param("page", "2147483647"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.list").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/alerts", "/api/alerts/1", "/api/alerts/1/key-frame"})
    void anonymousRequestsReturnResult401(String url) throws Exception {
        mvc.perform(get(url)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void queryApiDoesNotAllowWritesEvenForAdministrator(String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), "/api/alerts/" + simulated).header("Authorization", adminToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
    }

    private long user(String username, String role, Long fleet, Long vehicle) {
        long id = insert("INSERT INTO sys_user(username,password,name,fleet_id,vehicle_id,role) VALUES(?,?,?,?,?,?)",
                username, "fixture-account-no-login", "查询测试账号", fleet, vehicle, role);
        users.add(id); return id;
    }

    private long alert(long vehicle, Long driver, long fleet, String type, String sms, String behavior,
                       String clock, String keyFrame) {
        LocalDateTime captured = LocalDateTime.parse("2026-11-11T" + clock);
        long recognition = insert("INSERT INTO recognition_record(source_key,source_frame_id,camera_id,vehicle_id,driver_id,"
                        + "fleet_id,captured_at,pred,behavior_code,max_p,ood,participated,decision_reason,speed_status)"
                        + " VALUES(?,1,1,?,?,?,?,1,?,0.99,0,1,'EVALUATED','UNKNOWN')",
                UUID.randomUUID().toString(), vehicle, driver, fleet, captured, behavior);
        recognitions.add(recognition);
        long id = insert("INSERT INTO alert_record(recognition_id,camera_id,vehicle_id,driver_id,fleet_id,captured_at,"
                        + "behavior_code,max_p,record_type,event_count,sms_status,key_frame_path) VALUES(?,1,?,?,?,?,?,0.99,?,5,?,?)",
                recognition, vehicle, driver, fleet, captured, behavior, type, sms, keyFrame);
        alerts.add(id); return id;
    }

    private long insert(String sql, Object... parameters) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < parameters.length; i++) { statement.setObject(i + 1, parameters[i]); }
            return statement;
        }, key);
        assertThat(key.getKey()).isNotNull();
        return key.getKey().longValue();
    }
}
