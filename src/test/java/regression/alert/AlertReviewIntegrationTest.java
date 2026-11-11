package regression.alert;

import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import entity.AlertReviewRecord;
import mapper.AlertReviewMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class)
@AutoConfigureMockMvc
class AlertReviewIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @MockitoSpyBean AlertReviewMapper reviewMapper;
    private final List<Long> alerts = new ArrayList<>(), recognitions = new ArrayList<>(), users = new ArrayList<>();
    private long fleet, vehicle, driver, admin, secondAdmin, phone, backlog, unknown;
    private String adminToken, secondAdminToken, driverToken, otherDriverToken, adminUsername;

    @BeforeEach
    void fixtures() {
        String key = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        fleet = insert("INSERT INTO fleet(fleet_no,name) VALUES (?,?)", "review-" + key, "处理测试车队");
        vehicle = insert("INSERT INTO vehicle(fleet_id,plate_no) VALUES (?,?)", fleet, "R" + key.substring(0, 10));
        driver = user("review-d-" + key, "DRIVER");
        long otherDriver = user("review-other-" + key, "DRIVER");
        adminUsername = "review-a-" + key;
        admin = user(adminUsername, "PLATFORM_ADMIN");
        secondAdmin = user("review-b-" + key, "PLATFORM_ADMIN");
        adminToken = token(admin); secondAdminToken = token(secondAdmin);
        driverToken = token(driver); otherDriverToken = token(otherDriver);
        phone = alert("SMS_ALERT", "SIMULATED", "c1");
        backlog = alert("BACKLOG", "NOT_REQUIRED", "c4");
        unknown = alert("SPEED_UNKNOWN", "NOT_REQUIRED", null);
    }

    @AfterEach
    void cleanup() {
        for (Long id : alerts) { jdbc.update("DELETE FROM alert_review_record WHERE alert_id=?", id); }
        for (Long id : alerts) { jdbc.update("DELETE FROM alert_record WHERE id=?", id); }
        for (Long id : recognitions) { jdbc.update("DELETE FROM recognition_record WHERE id=?", id); }
        for (Long id : users) { jdbc.update("DELETE FROM sys_user WHERE id=?", id); }
        if (vehicle != 0) { jdbc.update("DELETE FROM vehicle WHERE id=?", vehicle); }
        if (fleet != 0) { jdbc.update("DELETE FROM fleet WHERE id=?", fleet); }
    }

    @Test
    void confirmSavesActorTimeAndReasonAndDriverCanReadResult() throws Exception {
        LocalDateTime before = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1);
        mvc.perform(post(url(phone)).header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\",\"reason\":\"  人工核实手机行为  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fromStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.toStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.reason").value("人工核实手机行为"))
                .andExpect(jsonPath("$.data.operatorId").value(admin))
                .andExpect(jsonPath("$.data.operatorUsername").value(adminUsername));
        var time = jdbc.queryForObject("SELECT operated_at FROM alert_review_record WHERE alert_id=?", LocalDateTime.class, phone);
        assertThat(time).isBetween(before, LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(1));
        mvc.perform(get("/api/alerts/" + phone).header("Authorization", driverToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.smsStatus").value("SIMULATED"));
        // 账号后来改名，历史操作人快照仍保持原值。
        jdbc.update("UPDATE sys_user SET username=?,name=? WHERE id=?", "renamed-" + admin, "改名后", admin);
        mvc.perform(get(url(phone)).header("Authorization", driverToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].operatorUsername").value(adminUsername))
                .andExpect(jsonPath("$.data[0].operatorName").value("处理测试账号"));
    }

    @Test
    void revokePreservesRecognitionAndSmsAndSupportsStatusFilter() throws Exception {
        var prior = jdbc.queryForMap("SELECT * FROM recognition_record WHERE id=(SELECT recognition_id FROM alert_record WHERE id=?)", backlog);
        review(backlog, "REVOKED", adminToken).andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT * FROM recognition_record WHERE id=(SELECT recognition_id FROM alert_record WHERE id=?)", backlog)).isEqualTo(prior);
        assertThat(jdbc.queryForObject("SELECT sms_status FROM alert_record WHERE id=?", String.class, backlog)).isEqualTo("NOT_REQUIRED");
        mvc.perform(get("/api/alerts").header("Authorization", driverToken).param("status", "REVOKED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(backlog));
        mvc.perform(get("/api/alerts").header("Authorization", driverToken).param("status", "OPEN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void speedUnknownCanBeHandledButNeverBecomesViolation() throws Exception {
        review(unknown, "CONFIRMED", adminToken).andExpect(status().isOk());
        mvc.perform(get("/api/alerts/" + unknown).header("Authorization", driverToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.violation").value(false));
    }

    @Test
    void driverCannotWriteOrReadAnotherDriversHistory() throws Exception {
        review(phone, "REVOKED", driverToken).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(get(url(phone)).header("Authorization", otherDriverToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        mvc.perform(get(url(phone)).header("Authorization", driverToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        assertThat(currentStatus(phone)).isEqualTo("OPEN");
        assertThat(reviewCount(phone)).isZero();
    }

    @Test
    void anonymousRequestsAreUnauthorized() throws Exception {
        mvc.perform(get(url(phone))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        review(phone, "CONFIRMED", null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void repeatedOrOppositeActionCannotOverwriteDecision() throws Exception {
        review(phone, "CONFIRMED", adminToken).andExpect(status().isOk());
        review(phone, "CONFIRMED", adminToken).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        review(phone, "REVOKED", secondAdminToken).andExpect(status().isConflict());
        assertThat(currentStatus(phone)).isEqualTo("CONFIRMED");
        assertThat(reviewCount(phone)).isEqualTo(1);
    }

    @Test
    void twoAdministratorsRaceOnlyOneCommits() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> confirm = executor.submit(() -> { start.await(); return review(phone, "CONFIRMED", adminToken).andReturn().getResponse().getStatus(); });
            Future<Integer> revoke = executor.submit(() -> { start.await(); return review(phone, "REVOKED", secondAdminToken).andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(List.of(confirm.get(10, TimeUnit.SECONDS), revoke.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(reviewCount(phone)).isEqualTo(1);
            var saved = jdbc.queryForMap("SELECT to_status,operator_id FROM alert_review_record WHERE alert_id=?", phone);
            assertThat(saved.get("to_status")).isEqualTo(currentStatus(phone));
            assertThat(((Number) saved.get("operator_id")).longValue()).isEqualTo("CONFIRMED".equals(currentStatus(phone)) ? admin : secondAdmin);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void historyFailureRollsBackStatusAndCanBeRetried() throws Exception {
        doThrow(new DataAccessResourceFailureException("injected history storage failure"))
                .when(reviewMapper).insertReview(any(AlertReviewRecord.class));
        review(phone, "CONFIRMED", adminToken).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500));
        assertThat(currentStatus(phone)).isEqualTo("OPEN");
        assertThat(reviewCount(phone)).isZero();
        reset(reviewMapper);
        review(phone, "REVOKED", adminToken).andExpect(status().isOk());
        assertThat(currentStatus(phone)).isEqualTo("REVOKED");
        assertThat(reviewCount(phone)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\":\"OPEN\",\"reason\":\"错误状态\"}",
            "{\"status\":\"INVALID\",\"reason\":\"错误枚举\"}", "{\"status\":\"CONFIRMED\"}",
            "{\"status\":\"CONFIRMED\",\"reason\":\"   \"}", "{\"status\":\"CONFIRMED\",\"reason\":\"　\"}",
            "{\"status\":null,\"reason\":\"空状态\"}", "not-json"})
    void invalidBodyDoesNotWrite(String body) throws Exception {
        mvc.perform(post(url(phone)).header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        assertThat(currentStatus(phone)).isEqualTo("OPEN");
        assertThat(reviewCount(phone)).isZero();
    }

    @Test
    void reasonBoundaryAndInvalidIds() throws Exception {
        mvc.perform(post(url(phone)).header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\",\"reason\":\"" + "核".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url(phone)).header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\",\"reason\":\"" + "核".repeat(500) + "\"}"))
                .andExpect(status().isOk());
        review(Long.MAX_VALUE, "CONFIRMED", adminToken).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        review(0, "CONFIRMED", adminToken).andExpect(status().isBadRequest());
        mvc.perform(get("/api/alerts/abc/reviews").header("Authorization", adminToken)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/alerts").header("Authorization", adminToken).param("status", "INVALID"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    private org.springframework.test.web.servlet.ResultActions review(long id, String status, String token) throws Exception {
        var request = post(url(id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\",\"reason\":\"人工核查\"}");
        if (token != null) { request.header("Authorization", token); }
        return mvc.perform(request);
    }
    private String url(long id) { return "/api/alerts/" + id + "/reviews"; }
    private String token(long id) { return "Bearer " + jwt.generateToken(id, 0); }
    private String currentStatus(long id) { return jdbc.queryForObject("SELECT status FROM alert_record WHERE id=?", String.class, id); }
    private int reviewCount(long id) { return jdbc.queryForObject("SELECT COUNT(*) FROM alert_review_record WHERE alert_id=?", Integer.class, id); }
    private long user(String username, String role) {
        long id = insert("INSERT INTO sys_user(username,password,name,fleet_id,vehicle_id,role) VALUES(?,?,?,?,?,?)",
                username, "fixture-no-login", "处理测试账号", fleet, vehicle, role);
        users.add(id); return id;
    }
    private long alert(String type, String sms, String behavior) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        long recognition = insert("INSERT INTO recognition_record(source_key,source_frame_id,vehicle_id,driver_id,fleet_id,captured_at,"
                        + "pred,behavior_code,max_p,ood,participated,decision_reason,speed_status) VALUES(?,1,?,?,?,?,1,?,0.99,0,1,'EVALUATED','UNKNOWN')",
                UUID.randomUUID().toString(), vehicle, driver, fleet, now, behavior);
        recognitions.add(recognition);
        long id = insert("INSERT INTO alert_record(recognition_id,vehicle_id,driver_id,fleet_id,captured_at,behavior_code,max_p,record_type,sms_status)"
                        + " VALUES(?,?,?,?,?,?,0.99,?,?)", recognition, vehicle, driver, fleet, now, behavior, type, sms);
        alerts.add(id); return id;
    }
    private long insert(String sql, Object... args) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) { statement.setObject(i + 1, args[i]); }
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
