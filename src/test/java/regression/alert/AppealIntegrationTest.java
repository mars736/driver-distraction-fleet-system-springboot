package regression.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import entity.AppealEvent;
import mapper.AppealMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class)
@AutoConfigureMockMvc
class AppealIntegrationTest {
    @TempDir static Path uploads;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("file.upload-dir", () -> uploads.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @Autowired SqlSessionTemplate sqlSession;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean AppealMapper mapper;
    private final List<Long> alerts = new ArrayList<>(), recognitions = new ArrayList<>(), users = new ArrayList<>();
    private long fleet, vehicle, driver, other, admin, openAlert, confirmedAlert, foreignAlert, speedAlert;
    private long ordinary, normal, ood, low, foreignRecognition;
    private String driverToken, otherToken, adminToken;

    @BeforeEach void fixtures() {
        String key = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        fleet = insert("INSERT INTO fleet(fleet_no,name) VALUES(?,?)", "appeal-" + key, "申诉测试车队");
        vehicle = insert("INSERT INTO vehicle(fleet_id,plate_no) VALUES(?,?)", fleet, "AP" + key.substring(0, 10));
        driver = user("appeal-d-" + key, "DRIVER"); other = user("appeal-o-" + key, "DRIVER"); admin = user("appeal-a-" + key, "PLATFORM_ADMIN");
        driverToken = token(driver); otherToken = token(other); adminToken = token(admin);
        openAlert = alert(driver, "SMS_ALERT", "OPEN"); confirmedAlert = alert(driver, "BACKLOG", "CONFIRMED");
        foreignAlert = alert(other, "SMS_ALERT", "OPEN"); speedAlert = alert(driver, "SPEED_UNKNOWN", "OPEN");
        ordinary = recognition(driver, "c2", false, true); normal = recognition(driver, "c0", false, true);
        ood = recognition(driver, "c2", true, false); low = recognition(driver, "c4", false, false);
        foreignRecognition = recognition(other, "c2", false, true);
    }

    @AfterEach void cleanup() throws Exception {
        // 仅删除属于本用例车队的夹具；文件目录由 @TempDir 独立创建。
        jdbc.update("DELETE e FROM alert_appeal_event e JOIN alert_appeal a ON a.id=e.appeal_id WHERE a.fleet_id=?", fleet);
        jdbc.update("DELETE m FROM alert_appeal_material m JOIN alert_appeal a ON a.id=m.appeal_id WHERE a.fleet_id=?", fleet);
        jdbc.update("DELETE FROM alert_appeal WHERE fleet_id=?", fleet);
        for (Long id : alerts) { jdbc.update("DELETE FROM alert_review_record WHERE alert_id=?", id); jdbc.update("DELETE FROM alert_record WHERE id=?", id); }
        for (Long id : recognitions) { jdbc.update("DELETE FROM recognition_record WHERE id=?", id); }
        for (Long id : users) { jdbc.update("DELETE FROM sys_user WHERE id=?", id); }
        if (vehicle != 0) { jdbc.update("DELETE FROM vehicle WHERE id=?", vehicle); }
        if (fleet != 0) { jdbc.update("DELETE FROM fleet WHERE id=?", fleet); }
        try (var paths = Files.walk(uploads)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) { Files.deleteIfExists(path); }
        }
    }

    @Test void submissionAndHistoryUseServerIdentityAndTime() throws Exception {
        long id = create("ALERT", openAlert, driverToken);
        mvc.perform(get("/api/appeals/" + id).header("Authorization", driverToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING")).andExpect(jsonPath("$.data.driverId").value(driver))
                .andExpect(jsonPath("$.data.reason").value("请求人工复核")).andExpect(jsonPath("$.data.targetStatusBefore").value("OPEN"));
        mvc.perform(get("/api/appeals/" + id + "/events").header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].action").value("SUBMITTED")).andExpect(jsonPath("$.data[0].operatorId").value(driver))
                .andExpect(jsonPath("$.data[0].operatorRole").value("DRIVER"));
        var time = jdbc.queryForObject("SELECT create_time FROM alert_appeal WHERE id=?", LocalDateTime.class, id);
        assertThat(time).isBetween(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(1), LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(1));
    }

    @Test void confirmedAlertCanBeRevokedAtomicallyWithoutChangingRecognitionOrSms() throws Exception {
        long rec = jdbc.queryForObject("SELECT recognition_id FROM alert_record WHERE id=?", Long.class, confirmedAlert);
        var before = jdbc.queryForMap("SELECT * FROM recognition_record WHERE id=?", rec);
        long id = create("ALERT", confirmedAlert, driverToken);
        decide(id, "REVOKED").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REVOKED"));
        assertThat(alertStatus(confirmedAlert)).isEqualTo("REVOKED");
        mvc.perform(get("/api/appeals/" + id).header("Authorization", driverToken))
                .andExpect(jsonPath("$.data.effectiveStatus").value("REVOKED"));
        mvc.perform(get("/api/recognitions/" + rec).header("Authorization", driverToken))
                .andExpect(jsonPath("$.data.effectiveStatus").value("REVOKED"));
        assertThat(jdbc.queryForMap("SELECT * FROM recognition_record WHERE id=?", rec)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT sms_status FROM alert_record WHERE id=?", String.class, confirmedAlert)).isEqualTo("SIMULATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_review_record WHERE alert_id=? AND from_status='CONFIRMED' AND to_status='REVOKED'", Integer.class, confirmedAlert)).isEqualTo(1);
        mvc.perform(get("/api/appeals/" + id + "/events").header("Authorization", driverToken))
                .andExpect(jsonPath("$.data[1].alertReviewId").isNumber()).andExpect(jsonPath("$.data[1].operatorId").value(admin));
        decide(id, "CONFIRMED").andExpect(status().isConflict());
        withdraw(id, driverToken).andExpect(status().isConflict());
    }

    @Test void upholdAlreadyConfirmedAlertRecordsAnotherReviewWithoutOverwritingHistory() throws Exception {
        long id = create("ALERT", confirmedAlert, driverToken);
        decide(id, "CONFIRMED").andExpect(status().isOk());
        assertThat(alertStatus(confirmedAlert)).isEqualTo("CONFIRMED");
        assertThat(appealStatus(id)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_review_record WHERE alert_id=?", Integer.class, confirmedAlert)).isEqualTo(1);
    }

    @Test void ordinaryDistractionCanBeAppealedWithoutRewritingRawPrediction() throws Exception {
        var before = jdbc.queryForMap("SELECT * FROM recognition_record WHERE id=?", ordinary);
        long id = create("RECOGNITION", ordinary, driverToken);
        decide(id, "REVOKED").andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT * FROM recognition_record WHERE id=?", ordinary)).isEqualTo(before);
        mvc.perform(get("/api/appeals/" + id).header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.effectiveStatus").value("REVOKED"));
        mvc.perform(get("/api/recognitions/" + ordinary).header("Authorization", driverToken))
                .andExpect(jsonPath("$.data.effectiveStatus").value("REVOKED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_review_record WHERE operator_id=?", Integer.class, admin)).isZero();
        submit("RECOGNITION", ordinary, driverToken).andExpect(status().isConflict());
    }

    @Test void invalidAndForeignTargetsCannotBeAppealed() throws Exception {
        for (long id : List.of(normal, ood, low)) { submit("RECOGNITION", id, driverToken).andExpect(status().isBadRequest()); }
        submit("ALERT", speedAlert, driverToken).andExpect(status().isBadRequest());
        submit("ALERT", foreignAlert, driverToken).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        submit("RECOGNITION", foreignRecognition, driverToken).andExpect(status().isNotFound());
        submit("ALERT", Long.MAX_VALUE, driverToken).andExpect(status().isNotFound());
        long linked = jdbc.queryForObject("SELECT recognition_id FROM alert_record WHERE id=?", Long.class, openAlert);
        submit("RECOGNITION", linked, driverToken).andExpect(status().isConflict());
    }

    @Test void newlyCommittedRecognitionWithAlertCannotBypassTargetQualification() throws Exception {
        var inserted = new CountDownLatch(1);
        var beforeTargetRead = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        var targetId = new AtomicLong();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var producer = executor.submit(() -> {
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                        long rec = recognition(driver, "c2", false, true);
                        long alert = insert("INSERT INTO alert_record(recognition_id,vehicle_id,driver_id,fleet_id,captured_at,behavior_code,record_type,sms_status) VALUES(?,?,?,?,?,'c2','BACKLOG','NOT_REQUIRED')",
                                rec, vehicle, driver, fleet, LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
                        alerts.add(alert); targetId.set(rec); inserted.countDown();
                        try { if (!beforeTargetRead.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("申诉未进入校验"); } }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    });
                } finally { committed.countDown(); }
            });
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var realMapper = sqlSession.getMapper(AppealMapper.class);
            // 此时申诉已读取用户、建立快照，再让模拟推理事务提交识别帧和告警。
            doAnswer(call -> {
                beforeTargetRead.countDown();
                if (!committed.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("推理事务未提交"); }
                return realMapper.lockRecognition(call.getArgument(0));
            }).when(mapper).lockRecognition(eq(targetId.get()));
            submit("RECOGNITION", targetId.get(), driverToken).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
            producer.get(15, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_appeal WHERE target_type='RECOGNITION' AND target_id=?", Integer.class, targetId.get())).isZero();
        } finally { beforeTargetRead.countDown(); executor.shutdownNow(); }
    }

    @Test void duplicateSubmissionBlockedButWithdrawnAppealCanBeSubmittedAgain() throws Exception {
        long first = create("ALERT", openAlert, driverToken);
        submit("ALERT", openAlert, driverToken).andExpect(status().isConflict());
        withdraw(first, driverToken).andExpect(status().isOk());
        assertThat(alertStatus(openAlert)).isEqualTo("OPEN");
        long second = create("ALERT", openAlert, driverToken);
        assertThat(second).isGreaterThan(first);
        assertThat(appealStatus(first)).isEqualTo("WITHDRAWN");
        mvc.perform(get("/api/appeals/" + first).header("Authorization", driverToken))
                .andExpect(jsonPath("$.data.effectiveStatus").value("EFFECTIVE"));
        decide(first, "REVOKED").andExpect(status().isConflict());
    }

    @Test void supplementsAppendAndTerminalAppealsRejectChanges() throws Exception {
        long id = create("ALERT", openAlert, driverToken);
        mvc.perform(post("/api/appeals/" + id + "/supplements").header("Authorization", driverToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"补充情况说明\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.action").value("SUPPLEMENTED"));
        assertThat(jdbc.queryForObject("SELECT reason FROM alert_appeal WHERE id=?", String.class, id)).isEqualTo("请求人工复核");
        withdraw(id, driverToken).andExpect(status().isOk());
        mvc.perform(post("/api/appeals/" + id + "/supplements").header("Authorization", driverToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"不应追加\"}")).andExpect(status().isConflict());
        upload(id, "pdf", driverToken).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_appeal_event WHERE appeal_id=?", Integer.class, id)).isEqualTo(3);
    }

    @Test void ownershipAppliesToAllReadsAndDriverIdCannotExpandList() throws Exception {
        long id = create("ALERT", openAlert, driverToken);
        long foreign = create("ALERT", foreignAlert, otherToken);
        for (String tail : List.of("", "/events", "/materials", "/materials/1")) {
            mvc.perform(get("/api/appeals/" + id + tail).header("Authorization", otherToken)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/appeals").header("Authorization", driverToken).param("driverId", "" + other))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/appeals").header("Authorization", driverToken).param("owner", "" + other).param("pageSize", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.list[0].id").value(id));
        mvc.perform(get("/api/appeals").header("Authorization", adminToken).param("driverId", "" + other).param("status", "PENDING").param("targetId", "" + foreignAlert))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.list[0].id").value(foreign));
        withdraw(id, otherToken).andExpect(status().isNotFound());
    }

    @Test void rolesAreEnforcedForEveryMutation() throws Exception {
        long id = create("ALERT", openAlert, driverToken);
        submit("ALERT", confirmedAlert, adminToken).andExpect(status().isForbidden());
        mvc.perform(post("/api/appeals/" + id + "/decision").header("Authorization", driverToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"REVOKED\",\"reason\":\"伪造管理员\"}")).andExpect(status().isForbidden());
        withdraw(id, adminToken).andExpect(status().isForbidden());
        mvc.perform(post("/api/appeals/" + id + "/supplements").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"伪造补充\"}")).andExpect(status().isForbidden());
        upload(id, "pdf", adminToken).andExpect(status().isForbidden());
        mvc.perform(delete("/api/appeals/" + id).header("Authorization", adminToken)).andExpect(status().isForbidden());
    }

    @Test void anonymousRequestsAre401AndDisabledTokenCannotRead() throws Exception {
        long id = create("ALERT", openAlert, driverToken);
        for (String path : List.of("/api/appeals", "/api/appeals/" + id, "/api/appeals/" + id + "/events", "/api/appeals/" + id + "/materials")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        }
        mvc.perform(post("/api/appeals").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        jdbc.update("UPDATE sys_user SET enabled=0 WHERE id=?", driver);
        mvc.perform(get("/api/appeals/" + id).header("Authorization", driverToken)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(strings = {"pdf", "png", "jpg"})
    void materialsDownloadRequiresOwnershipAndNeverExposesDiskPath(String type) throws Exception {
        long id = create("ALERT", openAlert, driverToken);
        var result = upload(id, type, driverToken).andExpect(status().isOk()).andExpect(jsonPath("$.data.relativePath").doesNotExist())
                .andExpect(jsonPath("$.data.originalName").value("evidence." + type)).andReturn();
        long material = json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
        String url = "/api/appeals/" + id + "/materials/" + material;
        mvc.perform(get(url).header("Authorization", driverToken)).andExpect(status().isOk())
                .andExpect(content().bytes(bytes(type))).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(head(url).header("Authorization", adminToken)).andExpect(status().isOk()).andExpect(content().string(""));
        mvc.perform(get(url).header("Authorization", otherToken)).andExpect(status().isNotFound());
        String fileType = switch (type) {
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            default -> "application/pdf";
        };
        // 下载客户端常会指定只接受文件格式，错误仍应按接口约定返回 404 + JSON。
        mvc.perform(get(url).header("Authorization", otherToken).accept(fileType))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(404));
        mvc.perform(get("/api/appeals/" + id + "/materials/" + Long.MAX_VALUE).header("Authorization", driverToken).accept(fileType))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/appeals/" + id + "/materials").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data[0].downloadUrl").value(url))
                .andExpect(jsonPath("$.data[0].availability").value("AVAILABLE"));
        String relative = jdbc.queryForObject("SELECT relative_path FROM alert_appeal_material WHERE id=?", String.class, material);
        mvc.perform(get("/upload/" + relative).header("Authorization", driverToken)).andExpect(status().isForbidden());
        mvc.perform(get("/upload/" + relative.replace("appeals/", "APPEALS/")).header("Authorization", driverToken)).andExpect(status().isNotFound());
        jdbc.update("UPDATE alert_appeal_material SET relative_path='../outside.pdf' WHERE id=?", material);
        mvc.perform(get("/api/appeals/" + id + "/materials").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data[0].id").value(material))
                .andExpect(jsonPath("$.data[0].availability").value("UNAVAILABLE"));
        mvc.perform(get(url).header("Authorization", adminToken)).andExpect(status().isNotFound());
    }

    @Test void badAndOversizedMaterialsAreRejectedAndLimitIsEnforced() throws Exception {
        long id = create("ALERT", openAlert, driverToken);
        for (byte[] content : List.of(new byte[0], "<html>fake</html>".getBytes())) {
            mvc.perform(multipart("/api/appeals/" + id + "/materials").file(new MockMultipartFile("file", "fake.jpg", "image/jpeg", content))
                    .header("Authorization", driverToken)).andExpect(status().isBadRequest());
        }
        mvc.perform(multipart("/api/appeals/" + id + "/materials").file(new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[1024*1024+1]))
                .header("Authorization", driverToken)).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(413));
        assertThat(fileCount()).isZero();
        for (int i = 0; i < 10; i++) { upload(id, "pdf", driverToken).andExpect(status().isOk()); }
        upload(id, "pdf", driverToken).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_appeal_material WHERE appeal_id=?", Integer.class, id)).isEqualTo(10);
    }

    @Test void databaseFailureRollsBackMaterialMetadataEventAndFile() throws Exception {
        long id = create("ALERT", openAlert, driverToken);
        doThrow(new DataAccessResourceFailureException("injected event failure")).when(mapper).insertEvent(any(AppealEvent.class));
        upload(id, "pdf", driverToken).andExpect(status().isInternalServerError());
        assertThat(fileCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_appeal_material WHERE appeal_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_appeal_event WHERE appeal_id=?", Integer.class, id)).isEqualTo(1);
        reset(mapper);
        upload(id, "pdf", driverToken).andExpect(status().isOk());
    }

    @Test void concurrentUploadsWithOneSlotLeftCannotExceedMaterialLimit() throws Exception {
        long id = create("ALERT", openAlert, driverToken);
        for (int i = 0; i < 9; i++) { upload(id, "pdf", driverToken).andExpect(status().isOk()); }
        var bothReadAppeal = new CyclicBarrier(2);
        var realMapper = sqlSession.getMapper(AppealMapper.class);
        // 强制两个事务都先读到旧快照，再去竞争目标锁，不能靠请求偶然先后来通过。
        doAnswer(call -> {
            var appeal = realMapper.find(call.getArgument(0), call.getArgument(1));
            bothReadAppeal.await(10, TimeUnit.SECONDS);
            return appeal;
        }).when(mapper).find(eq(id), any());
        race(() -> upload(id, "pdf", driverToken), () -> upload(id, "pdf", driverToken));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_appeal_material WHERE appeal_id=?", Integer.class, id)).isEqualTo(10);
        assertThat(fileCount()).isEqualTo(10);
    }

    @Test void decisionEventFailureRollsBackAlertReviewAndAppealThenRetrySucceeds() throws Exception {
        long id = create("ALERT", confirmedAlert, driverToken);
        doThrow(new DataAccessResourceFailureException("injected event failure")).when(mapper).insertEvent(any(AppealEvent.class));
        decide(id, "REVOKED").andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500));
        assertThat(appealStatus(id)).isEqualTo("PENDING"); assertThat(alertStatus(confirmedAlert)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_review_record WHERE alert_id=?", Integer.class, confirmedAlert)).isZero();
        reset(mapper);
        decide(id, "REVOKED").andExpect(status().isOk());
    }

    @Test void creationEventFailureDoesNotLeavePendingAppeal() throws Exception {
        doThrow(new DataAccessResourceFailureException("injected event failure")).when(mapper).insertEvent(any(AppealEvent.class));
        submit("ALERT", openAlert, driverToken).andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_appeal WHERE target_type='ALERT' AND target_id=?", Integer.class, openAlert)).isZero();
        reset(mapper);
        create("ALERT", openAlert, driverToken);
    }

    @Test void concurrentSubmissionsOnlyCreateOnePendingAppeal() throws Exception {
        race(() -> submit("ALERT", openAlert, driverToken), () -> submit("ALERT", openAlert, driverToken));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_appeal WHERE target_type='ALERT' AND target_id=?", Integer.class, openAlert)).isEqualTo(1);
    }

    @Test void withdrawAndDecisionRaceOnlyOneCanWin() throws Exception {
        long id = create("ALERT", confirmedAlert, driverToken);
        race(() -> withdraw(id, driverToken), () -> decide(id, "REVOKED"));
        assertThat(appealStatus(id)).isIn("WITHDRAWN", "REVOKED");
        assertThat(alertStatus(confirmedAlert)).isEqualTo("REVOKED".equals(appealStatus(id)) ? "REVOKED" : "CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_appeal_event WHERE appeal_id=?", Integer.class, id)).isEqualTo(2);
    }

    @Test void competingDecisionsCannotOverwriteEachOther() throws Exception {
        long id = create("ALERT", openAlert, driverToken);
        race(() -> decide(id, "REVOKED"), () -> decide(id, "CONFIRMED"));
        assertThat(alertStatus(openAlert)).isEqualTo(appealStatus(id));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_review_record WHERE alert_id=?", Integer.class, openAlert)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_appeal_event WHERE appeal_id=?", Integer.class, id)).isEqualTo(2);
    }

    @Test void invalidDecisionAndSupplementLeaveAppealPending() throws Exception {
        long id = create("ALERT", openAlert, driverToken);
        decide(id, "OPEN").andExpect(status().isBadRequest());
        mvc.perform(post("/api/appeals/" + id + "/supplements").header("Authorization", driverToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"　\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/appeals").header("Authorization", driverToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"ALERT\",\"targetId\":" + confirmedAlert + ",\"reason\":\"" + "长".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(appealStatus(id)).isEqualTo("PENDING");
        assertThat(alertStatus(openAlert)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_appeal_event WHERE appeal_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test void successfulReviewsCanBeRecoveredAfterLosingTheResponse() throws Exception {
        // 模拟服务端已提交，客户端未获取成功响应中的记录编号。
        directReview(openAlert, "CONFIRMED").andExpect(status().isOk());
        String alertPath = "/api/alerts/" + openAlert;
        var alertResult = mvc.perform(get(alertPath).header("Authorization", adminToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED")).andReturn();
        long originalReview = json.readTree(alertResult.getResponse().getContentAsString()).path("data").path("latestReviewId").asLong();
        assertThat(originalReview).isPositive();
        directReview(openAlert, "REVOKED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("ALREADY_REVIEWED"));
        mvc.perform(get(alertPath + "/reviews").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(originalReview))
                .andExpect(jsonPath("$.data[0].operatorId").value(admin));

        long appeal = create("ALERT", confirmedAlert, driverToken);
        decide(appeal, "REVOKED").andExpect(status().isOk());
        String appealPath = "/api/appeals/" + appeal;
        var appealResult = mvc.perform(get(appealPath).header("Authorization", adminToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED")).andReturn();
        long originalDecision = json.readTree(appealResult.getResponse().getContentAsString()).path("data").path("decisionEventId").asLong();
        assertThat(originalDecision).isPositive();
        decide(appeal, "REVOKED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("APPEAL_ALREADY_REVIEWED"))
                .andExpect(jsonPath("$.data.appealStatus").value("REVOKED"))
                .andExpect(jsonPath("$.data.decisionEventId").value(originalDecision));
        mvc.perform(get(appealPath + "/events").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].id").value(originalDecision))
                .andExpect(jsonPath("$.data[1].operatorId").value(admin));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_review_record WHERE alert_id=?",
                Integer.class, confirmedAlert)).isEqualTo(1);
    }

    @Test void withdrawnAppealReturnsItsStatusAndWithdrawalEventWhenReviewIsAttempted() throws Exception {
        long id = create("RECOGNITION", ordinary, driverToken);
        var result = withdraw(id, driverToken).andExpect(status().isOk()).andReturn();
        long withdrawal = json.readTree(result.getResponse().getContentAsString()).path("data").path("decisionEventId").asLong();
        assertThat(withdrawal).isPositive();
        decide(id, "CONFIRMED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("APPEAL_WITHDRAWN"))
                .andExpect(jsonPath("$.data.appealStatus").value("WITHDRAWN"))
                .andExpect(jsonPath("$.data.decisionEventId").value(withdrawal));
    }

    @Test void directAlertReviewIsBlockedWhileAppealIsPendingAndRevokedTargetCannotAppeal() throws Exception {
        long id = create("ALERT", openAlert, driverToken);
        directReview(openAlert, "CONFIRMED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("PENDING_APPEAL"))
                .andExpect(jsonPath("$.data.pendingAppealId").value(id));
        mvc.perform(get("/api/alerts/" + openAlert).header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.pendingAppealId").value(id));
        mvc.perform(get("/api/alerts/" + openAlert).header("Authorization", driverToken))
                .andExpect(jsonPath("$.data.pendingAppealId").doesNotExist());
        assertThat(alertStatus(openAlert)).isEqualTo("OPEN");
        withdraw(id, driverToken).andExpect(status().isOk());
        directReview(openAlert, "REVOKED").andExpect(status().isOk());
        directReview(openAlert, "CONFIRMED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("ALREADY_REVIEWED"));
        submit("ALERT", openAlert, driverToken).andExpect(status().isConflict());
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"targetType\":\"ALERT\",\"targetId\":-1,\"reason\":\"invalid\"}",
            "{\"targetType\":\"INVALID\",\"targetId\":1,\"reason\":\"invalid\"}",
            "{\"targetType\":\"ALERT\",\"targetId\":1,\"reason\":\"　\"}", "bad-json"})
    void invalidCreateRequestsReturn400(String body) throws Exception {
        mvc.perform(post("/api/appeals").header("Authorization", driverToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test void queryAndContentNegotiationKeepCorrectHttpStatuses() throws Exception {
        mvc.perform(get("/api/appeals").header("Authorization", driverToken).param("page", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/appeals").header("Authorization", driverToken).param("pageSize", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/appeals").header("Authorization", driverToken).param("status", "INVALID")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/appeals").header("Authorization", driverToken).param("page", "2147483647"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.list").isEmpty());
        mvc.perform(post("/api/appeals").header("Authorization", driverToken).contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(415));
        mvc.perform(get("/api/appeals").header("Authorization", driverToken).accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.code").value(406));
    }

    private long create(String type, long target, String token) throws Exception {
        var result = submit(type, target, token).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }
    private ResultActions submit(String type, long target, String token) throws Exception {
        return mvc.perform(post("/api/appeals").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"" + type + "\",\"targetId\":" + target + ",\"reason\":\"  请求人工复核  \",\"driverId\":999}"));
    }
    private ResultActions decide(long id, String state) throws Exception {
        return mvc.perform(post("/api/appeals/" + id + "/decision").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + state + "\",\"reason\":\"管理员复核结果\"}"));
    }
    private ResultActions directReview(long id, String state) throws Exception {
        return mvc.perform(post("/api/alerts/" + id + "/reviews").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + state + "\",\"reason\":\"直接处理\"}"));
    }
    private ResultActions withdraw(long id, String token) throws Exception { return mvc.perform(post("/api/appeals/" + id + "/withdraw").header("Authorization", token)); }
    private ResultActions upload(long id, String type, String token) throws Exception {
        return mvc.perform(multipart("/api/appeals/" + id + "/materials")
                .file(new MockMultipartFile("file", "../evidence." + type, "application/octet-stream", bytes(type))).header("Authorization", token));
    }
    private byte[] bytes(String type) {
        return switch (type) {
            case "png" -> new byte[]{(byte)137,80,78,71,13,10,26,10,0};
            case "jpg" -> new byte[]{(byte)255,(byte)216,(byte)255,0};
            default -> "%PDF-1.4\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        };
    }
    private long fileCount() throws Exception { try (var paths = Files.walk(uploads)) { return paths.filter(Files::isRegularFile).count(); } }
    private String appealStatus(long id) { return jdbc.queryForObject("SELECT status FROM alert_appeal WHERE id=?", String.class, id); }
    private String alertStatus(long id) { return jdbc.queryForObject("SELECT status FROM alert_record WHERE id=?", String.class, id); }
    private String token(long id) { return "Bearer " + jwt.generateToken(id, 0); }
    private void race(Callable<ResultActions> a, Callable<ResultActions> b) throws Exception {
        // 两个请求等同一声“开始”再执行，检验数据库锁，而不是先后调用制造的假并发。
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { start.await(); return a.call().andReturn().getResponse().getStatus(); });
            var second = executor.submit(() -> { start.await(); return b.call().andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { executor.shutdownNow(); }
    }
    private long user(String username, String role) {
        long id = insert("INSERT INTO sys_user(username,password,name,fleet_id,vehicle_id,role) VALUES(?,?,?,?,?,?)",
                username, "fixture-no-login", "申诉测试账号", fleet, vehicle, role); users.add(id); return id;
    }
    private long recognition(long owner, String behavior, boolean isOod, boolean participated) {
        long id = insert("INSERT INTO recognition_record(source_key,source_frame_id,vehicle_id,driver_id,fleet_id,captured_at,pred,behavior_code,max_p,ood,participated,decision_reason,speed_status)"
                        + " VALUES(?,1,?,?,?,?,2,?,0.99,?,?,?,'UNKNOWN')", UUID.randomUUID().toString(), vehicle, owner, fleet,
                LocalDateTime.now(ZoneId.of("Asia/Shanghai")), behavior, isOod, participated, participated ? "EVALUATED" : "LOW_SPEED");
        recognitions.add(id); return id;
    }
    private long alert(long owner, String type, String state) {
        long rec = recognition(owner, type.equals("SPEED_UNKNOWN") ? "c0" : "c1", false, true);
        long id = insert("INSERT INTO alert_record(recognition_id,vehicle_id,driver_id,fleet_id,captured_at,behavior_code,record_type,sms_status,status) VALUES(?,?,?,?,?,'c1',?,'SIMULATED',?)",
                rec, vehicle, owner, fleet, LocalDateTime.now(ZoneId.of("Asia/Shanghai")), type, state);
        alerts.add(id); return id;
    }
    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i=0;i<args.length;i++) { statement.setObject(i+1,args[i]); } return statement;
        }, key);
        return key.getKey().longValue();
    }
}
