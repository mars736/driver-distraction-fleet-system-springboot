package regression.score;

import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import mapper.DriverScoreMapper;
import org.mybatis.spring.SqlSessionTemplate;
import java.util.Map;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 MyBatis SQL + JWT + HTTP；默认使用独立内存库，绝不读取开发库数据。 */
@SpringBootTest(classes=DriverDistractionFleetSystemSpringbootApplication.class)
@AutoConfigureMockMvc
class DriverScoreIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        // CI 可切换到专用 MySQL 测试库；本地无 MySQL 时也能运行评分的完整 HTTP 链路。
        if (!"true".equals(System.getenv("DRIVER_SCORE_MYSQL_TESTS"))) {
            r.add("spring.datasource.url", () -> "jdbc:h2:mem:driver_score;MODE=MySQL;DB_CLOSE_DELAY=-1");
            r.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
            r.add("spring.datasource.username", () -> "sa");
            r.add("spring.datasource.password", () -> "");
            r.add("spring.sql.init.mode", () -> "always");
            r.add("spring.sql.init.schema-locations", DriverScoreIntegrationTest::h2Schema);
        } else {
            String url = System.getenv("DRIVER_SCORE_TEST_DB_URL");
            if (url == null || !url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):[0-9]+/driver_score_test(?:_[0-9]+)?([?].*)?")) {
                throw new IllegalStateException("评分 MySQL 测试只允许本机 driver_score_test 专用库");
            }
            r.add("spring.datasource.url", () -> url);
            r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
            r.add("spring.datasource.username", () -> System.getenv().getOrDefault("DRIVER_SCORE_TEST_DB_USER", "root"));
            r.add("spring.datasource.password", () -> System.getenv().getOrDefault("DRIVER_SCORE_TEST_DB_PASSWORD", ""));
            r.add("spring.sql.init.mode", () -> "never");
        }
    }
    private static String h2Schema() {
        try {
            String schema = new ClassPathResource("db/schema.sql").getContentAsString(StandardCharsets.UTF_8);
            // MySQL 的索引名只需在表内唯一，H2 要求在整个 schema 内唯一。
            // 测试从正式 DDL 生成副本，只给索引改名，不维护第二套业务表结构。
            var matcher = java.util.regex.Pattern.compile("(KEY )([a-z_]+)( \\()").matcher(schema);
            var copy = new StringBuffer(); int index = 0;
            while (matcher.find()) {
                matcher.appendReplacement(copy, matcher.group(1) + matcher.group(2) + "_" + index++ + matcher.group(3));
            }
            matcher.appendTail(copy);
            Path file = Files.createTempFile("driver-score-schema-", ".sql");
            file.toFile().deleteOnExit(); Files.writeString(file, copy.toString());
            return file.toUri().toString();
        } catch (java.io.IOException e) { throw new IllegalStateException("无法创建评分测试库", e); }
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean DriverScoreMapper scoreMapper;
    @Autowired SqlSessionTemplate sqlSession;
    long fleet, vehicle, driver, other, admin;
    String driverToken, adminToken;

    @BeforeEach void fixtures() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        fleet = insert("INSERT INTO fleet(fleet_no,name) VALUES(?,?)", "score-"+key,"评分测试车队");
        vehicle = insert("INSERT INTO vehicle(fleet_id,plate_no) VALUES(?,?)", fleet,"SC"+key);
        driver = user("score-d-"+key, "DRIVER"); other = user("score-o-"+key,"DRIVER");
        admin = user("score-a-"+key,"PLATFORM_ADMIN");
        driverToken = token(driver); adminToken = token(admin);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM alert_appeal_event WHERE appeal_id IN (SELECT id FROM alert_appeal WHERE fleet_id=?)",fleet);
        jdbc.update("DELETE FROM alert_appeal WHERE fleet_id=?",fleet);
        jdbc.update("DELETE FROM alert_review_record WHERE alert_id IN (SELECT id FROM alert_record WHERE fleet_id=?)",fleet);
        jdbc.update("DELETE FROM alert_record WHERE fleet_id=?",fleet);
        jdbc.update("DELETE FROM recognition_record WHERE fleet_id=?",fleet);
        jdbc.update("DELETE FROM operation_log WHERE fleet_id=?",fleet);
        jdbc.update("DELETE FROM sys_user WHERE fleet_id=?",fleet);
        jdbc.update("DELETE FROM vehicle WHERE id=?",vehicle);
        jdbc.update("DELETE FROM fleet WHERE id=?",fleet);
    }

    @Test void emptyMonthStartsAt100WithoutCreatingScoreRows() throws Exception {
        mvc.perform(get("/api/scores/me?month=2026-01").header("Authorization",driverToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.score").value(100))
                .andExpect(jsonPath("$.data.level").value("EXCELLENT"))
                .andExpect(jsonPath("$.data.deductedPoints").value(0))
                .andExpect(jsonPath("$.data.smsAlertCount").value(0))
                .andExpect(jsonPath("$.data.month").value("2026-01"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }
    @Test void onlyEffectiveMonthlyBehaviorAlertsDeductAndRequestsAreIdempotent() throws Exception {
        alert(driver,"SMS_ALERT","OPEN","2026-01-01 00:00:00");
        alert(driver,"BACKLOG","CONFIRMED","2026-01-31 23:59:59.999");
        alert(driver,"SMS_ALERT","REVOKED","2026-01-10 00:00:00");
        alert(driver,"SPEED_UNKNOWN","OPEN","2026-01-10 00:00:00");
        alert(driver,"SMS_ALERT","OPEN","2025-12-31 23:59:59.999");
        alert(driver,"SMS_ALERT","OPEN","2026-02-01 00:00:00");
        alert(other,"SMS_ALERT","OPEN","2026-01-10 00:00:00");
        // 普通识别帧不产生告警，不应“每帧扣一分”。
        recognition(driver,"2026-01-10 00:00:00");
        for (int i=0;i<2;i++) {
            summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(94))
                    .andExpect(jsonPath("$.data.deductedPoints").value(6))
                    .andExpect(jsonPath("$.data.revokedCount").value(1));
        }
    }
    @Test void directRevocationRemovesDeductionInOriginalMonth() throws Exception {
        long id = alert(driver,"SMS_ALERT","OPEN","2026-01-10 00:00:00");
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(95));
        review(id,"REVOKED").andExpect(status().isOk());
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(100));
        review(id,"REVOKED").andExpect(status().isConflict());
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(100));
        mvc.perform(get("/api/scores/drivers/"+driver+"?month=2026-02").header("Authorization",driverToken))
                .andExpect(jsonPath("$.data.score").value(100));
    }
    @Test void disablingDriverKeepsHistoricalScoreAndHistoryPreventsDeletion() throws Exception {
        alert(driver,"SMS_ALERT","OPEN","2026-01-10 00:00:00");
        mvc.perform(delete("/api/drivers/{id}",driver).header("Authorization",adminToken))
                .andExpect(jsonPath("$.code").value(409));
        mvc.perform(patch("/api/drivers/{id}/status",driver).header("Authorization",adminToken)
                        .param("enabled","false")).andExpect(status().isOk());
        mvc.perform(get("/api/scores/me").header("Authorization",driverToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/scores/drivers/{id}/detail",driver).header("Authorization",adminToken)
                        .param("month","2026-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.score").value(95))
                .andExpect(jsonPath("$.data.records.total").value(1))
                .andExpect(jsonPath("$.data.records.list[0].effectiveDeduction").value(5));
    }

    @Test void pendingAndWithdrawnAppealsKeepDeductionButRevokedAppealRemovesIt() throws Exception {
        long alert = alert(driver,"SMS_ALERT","CONFIRMED","2026-01-10 00:00:00");
        long appeal = appeal(alert);
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(95));
        mvc.perform(post("/api/appeals/"+appeal+"/withdraw").header("Authorization",driverToken)).andExpect(status().isOk());
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(95));
        appeal = appeal(alert);
        decide(appeal,"REVOKED").andExpect(status().isOk());
        decide(appeal,"REVOKED").andExpect(status().isConflict());
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(100));
        mvc.perform(get("/api/scores/drivers/"+driver+"/records?month=2026-01").header("Authorization",driverToken))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].originalDeduction").value(5))
                .andExpect(jsonPath("$.data.list[0].effectiveDeduction").value(0));
    }
    @Test void confirmedAppealAndExtraHistoryDoNotMultiplyDeduction() throws Exception {
        long id = alert(driver,"SMS_ALERT","CONFIRMED","2026-01-10 00:00:00");
        decide(appeal(id),"CONFIRMED").andExpect(status().isOk());
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(95))
                .andExpect(jsonPath("$.data.smsAlertCount").value(1));
    }
    @Test void lateRevocationRecalculatesOriginalMonthWithoutCreditingAnotherMonth() throws Exception {
        long january=alert(driver,"SMS_ALERT","OPEN","2026-01-10 00:00:00");
        alert(driver,"BACKLOG","OPEN","2026-02-10 00:00:00");
        review(january,"REVOKED").andExpect(status().isOk());
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(100));
        mvc.perform(get("/api/scores/me?month=2026-02").header("Authorization",driverToken))
                .andExpect(jsonPath("$.data.score").value(99));
    }
    @Test void withdrawingAppealKeepsOriginalDeduction() throws Exception {
        long id=alert(driver,"SMS_ALERT","OPEN","2026-01-10 00:00:00");
        long appeal=appeal(id);
        mvc.perform(post("/api/appeals/"+appeal+"/withdraw").header("Authorization",driverToken))
                .andExpect(status().isOk());
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(95));
    }
    @Test void revokingOrdinaryFrameNeverCreatesAnUnfundedCredit() throws Exception {
        long rec=recognition(driver,"2026-01-10 00:00:00");
        String body=mvc.perform(post("/api/appeals").header("Authorization",driverToken).contentType("application/json")
                .content("{\"targetType\":\"RECOGNITION\",\"targetId\":"+rec+",\"reason\":\"请求复核\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long appeal=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data").path("id").asLong();
        decide(appeal,"REVOKED").andExpect(status().isOk());
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(100))
                .andExpect(jsonPath("$.data.deductedPoints").value(0));
    }
    @Test void missingDriverAdminAccountAndNonPositiveIdsAreHandled() throws Exception {
        summary(Long.MAX_VALUE,adminToken).andExpect(status().isNotFound());
        summary(admin,adminToken).andExpect(status().isNotFound());
        summary(0,driverToken).andExpect(status().isBadRequest());
        mvc.perform(get("/api/scores/me").header("Authorization",adminToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/scores/ranking?fleetId="+Long.MAX_VALUE).header("Authorization",adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.list",hasSize(0)));
    }

    @Test void zeroFloorUsesRemainingDebtInsteadOfBlindlyAddingFive() throws Exception {
        long id=0;
        for(int i=0;i<22;i++) { id=alert(driver,"SMS_ALERT","OPEN","2026-01-10 00:00:00"); }
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(0))
                .andExpect(jsonPath("$.data.deductedPoints").value(110));
        review(id,"REVOKED").andExpect(status().isOk());
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(0))
                .andExpect(jsonPath("$.data.deductedPoints").value(105));
    }
    @Test void scoreUpdatesBecomeVisibleOnlyWhenReviewTransactionCommits() throws Exception {
        long id=alert(driver,"SMS_ALERT","OPEN","2026-01-10 00:00:00");
        var updated=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=executor.submit(() -> new TransactionTemplate(transactions).execute(s -> {
                jdbc.update("UPDATE alert_record SET status='REVOKED' WHERE id=?",id);
                updated.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) { throw new IllegalStateException("测试超时"); } }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return null;
            }));
            try {
                assertThat(updated.await(10,TimeUnit.SECONDS)).isTrue();
                summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(95));
            } finally { release.countDown(); }
            future.get(10,TimeUnit.SECONDS);
        }
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(100));
    }
    @Test void rolledBackReviewNeverChangesScore() throws Exception {
        long id=alert(driver,"BACKLOG","OPEN","2026-01-10 00:00:00");
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            jdbc.update("UPDATE alert_record SET status='REVOKED' WHERE id=?",id); s.setRollbackOnly();
        });
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(99));
    }
    @Test void reassignmentDoesNotMoveHistoricalDeductionToAnotherDriver() throws Exception {
        alert(driver,"SMS_ALERT","OPEN","2026-01-10 00:00:00");
        jdbc.update("UPDATE camera SET driver_id=? WHERE vehicle_id=?",other,vehicle);
        jdbc.update("UPDATE sys_user SET vehicle_id=? WHERE id=?",vehicle,other);
        summary(driver,driverToken).andExpect(jsonPath("$.data.score").value(95));
        summary(other,adminToken).andExpect(jsonPath("$.data.score").value(100));
    }
    @Test void recordsAreStablePagedAndKeepRevokedHistory() throws Exception {
        long first=alert(driver,"BACKLOG","OPEN","2026-01-10 00:00:00");
        long second=alert(driver,"SMS_ALERT","REVOKED","2026-01-10 00:00:00");
        mvc.perform(get("/api/scores/drivers/"+driver+"/records?month=2026-01&pageSize=1").header("Authorization",driverToken))
                .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.list[0].alertId").value(second));
        mvc.perform(get("/api/scores/drivers/"+driver+"/records?month=2026-01&pageSize=1&page=2").header("Authorization",driverToken))
                .andExpect(jsonPath("$.data.list[0].alertId").value(first));
        mvc.perform(get("/api/scores/drivers/"+driver+"/records?month=2026-01&page=2147483647").header("Authorization",driverToken))
                .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.list",hasSize(0)));
    }
    @Test void rankingIncludesNoAlertAndDisabledDriversWithSharedRanksAndStablePages() throws Exception {
        long third=user("score-third-"+UUID.randomUUID(),"DRIVER");
        jdbc.update("UPDATE sys_user SET enabled=0 WHERE id=?",third);
        alert(driver,"SMS_ALERT","OPEN","2026-01-10 00:00:00");
        String path="/api/scores/ranking?month=2026-01&fleetId="+fleet+"&pageSize=1";
        mvc.perform(get(path).header("Authorization",adminToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.list[0].driverId").value(other))
                .andExpect(jsonPath("$.data.list[0].ranking").value(1));
        mvc.perform(get(path+"&page=2").header("Authorization",adminToken))
                .andExpect(jsonPath("$.data.list[0].driverId").value(third)).andExpect(jsonPath("$.data.list[0].ranking").value(1));
        mvc.perform(get(path+"&page=3").header("Authorization",adminToken))
                .andExpect(jsonPath("$.data.list[0].driverId").value(driver)).andExpect(jsonPath("$.data.list[0].ranking").value(2));
    }
    @Test void driverCannotViewOtherDriversOrRankingAndAnonymousIsRejected() throws Exception {
        mvc.perform(get("/api/scores/me")).andExpect(status().isUnauthorized());
        for(String suffix:new String[]{"","/records"}) {
            mvc.perform(get("/api/scores/drivers/"+other+suffix).header("Authorization",driverToken)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/scores/ranking").header("Authorization",driverToken)).andExpect(status().isForbidden());
        summary(admin,adminToken).andExpect(status().isNotFound());
        summary(Long.MAX_VALUE,adminToken).andExpect(status().isNotFound());
        mvc.perform(get("/api/scores/me").header("Authorization",adminToken)).andExpect(status().isForbidden());
    }
    @ParameterizedTest
    @ValueSource(strings={"month=2026-13","month=2026-1","month=","month=9999-12","page=0","pageSize=101","fleetId=-1","page=no"})
    void badInputsReturn400InsteadOf500(String query) throws Exception {
        mvc.perform(get("/api/scores/me?"+query).header("Authorization",driverToken))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }
    @Test void unsupportedResponseFormatKeeps406() throws Exception {
        mvc.perform(get("/api/scores/me").accept("image/png").header("Authorization",driverToken))
                .andExpect(status().isNotAcceptable()).andExpect(content().contentTypeCompatibleWith("application/json"));
    }
    @Test void scoresHaveNoPublicWriteEndpoint() throws Exception {
        mvc.perform(post("/api/scores/drivers/"+driver).header("Authorization",driverToken)
                .contentType("application/json").content("{\"score\":100}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @ParameterizedTest
    @CsvSource({"0,0,100,EXCELLENT", "2,0,90,EXCELLENT", "2,1,89,GOOD", "5,0,75,GOOD",
            "5,1,74,FAIR", "8,0,60,FAIR", "8,1,59,POOR", "20,1,0,POOR"})
    void realSqlAndResponseAgreeAtEveryGradeBoundary(int sms,int backlog,int score,String level) throws Exception {
        for(int i=0;i<sms;i++) alert(driver,"SMS_ALERT","OPEN","2026-01-10 00:00:00");
        for(int i=0;i<backlog;i++) alert(driver,"BACKLOG","CONFIRMED","2026-01-10 00:00:00");
        summary(driver,driverToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(score)).andExpect(jsonPath("$.data.level").value(level))
                .andExpect(jsonPath("$.data.smsAlertCount").value(sms)).andExpect(jsonPath("$.data.backlogCount").value(backlog));
    }

    @ParameterizedTest
    @ValueSource(strings={"PENDING","SENDING","SENT","FAILED","SKIPPED_NO_PHONE"})
    void smsDeliveryOutcomeDoesNotChangeViolationScore(String smsStatus) throws Exception {
        long id=alert(driver,"SMS_ALERT","OPEN","2026-01-10 00:00:00");
        jdbc.update("UPDATE alert_record SET sms_status=? WHERE id=?",smsStatus,id);
        summary(driver,driverToken).andExpect(status().isOk()).andExpect(jsonPath("$.data.score").value(95));
    }

    @Test void revokedTokenOrUnrecognizedRoleCannotReadScores() throws Exception {
        jdbc.update("UPDATE sys_user SET token_version=1 WHERE id=?",driver);
        mvc.perform(get("/api/scores/me").header("Authorization",driverToken)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE sys_user SET token_version=0,role='OTHER' WHERE id=?",driver);
        mvc.perform(get("/api/scores/drivers/"+other).header("Authorization",driverToken)).andExpect(status().isForbidden());
    }

    @Test void fleetRankingUsesCurrentMembershipButPreservesAllHistoricalDeduction() throws Exception {
        String key=UUID.randomUUID().toString();
        long destination=insert("INSERT INTO fleet(fleet_no,name) VALUES(?,?)","score-move-"+key.substring(0,8),"新车队");
        try {
            alert(driver,"SMS_ALERT","OPEN","2026-01-10 00:00:00");
            jdbc.update("UPDATE sys_user SET fleet_id=? WHERE id=?",destination,driver);
            mvc.perform(get("/api/scores/ranking?month=2026-01&fleetId="+destination).header("Authorization",adminToken))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.list[0].driverId").value(driver))
                    .andExpect(jsonPath("$.data.list[0].score").value(95));
            mvc.perform(get("/api/scores/ranking?month=2026-01&fleetId="+fleet).header("Authorization",adminToken))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.list[0].driverId").value(other));
        } finally {
            jdbc.update("UPDATE sys_user SET fleet_id=? WHERE id=?",fleet,driver);
            jdbc.update("DELETE FROM fleet WHERE id=?",destination);
        }
    }

    @Test void recordsAndTotalShareSnapshotWhenAnotherTransactionAddsAnAlert() throws Exception {
        alert(driver,"BACKLOG","OPEN","2026-01-10 00:00:00");
        var pool=Executors.newSingleThreadExecutor();
        try {
            // 让新告警恰好在“列表查完、总数未查”之间提交，验证分页结果使用同一份快照。
            doAnswer(call -> {
                // Mapper 是接口代理，直接通过同一 SqlSession 执行真实 SQL，仍使用当前事务。
                var rows=sqlSession.selectList("mapper.DriverScoreMapper.records", Map.of(
                        "driverId", driver, "period", call.<Object>getArgument(1),
                        "offset", call.<Object>getArgument(2), "limit", call.<Object>getArgument(3)));
                var write=pool.submit(() -> alert(driver,"BACKLOG","OPEN","2026-01-11 00:00:00"));
                try { write.get(10,TimeUnit.SECONDS); } finally { write.cancel(true); }
                return rows;
            }).when(scoreMapper).records(eq(driver),any(),anyLong(),anyInt());
            mvc.perform(get("/api/scores/drivers/"+driver+"/records?month=2026-01").header("Authorization",driverToken))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.list",hasSize(1)))
                    .andExpect(jsonPath("$.data.total").value(1));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_record WHERE driver_id=?",Long.class,driver)).isEqualTo(2L);
        } finally { pool.shutdownNow(); }
    }

    @Test void rankingAndTotalShareSnapshotWhenAnotherTransactionAddsADriver() throws Exception {
        var pool=Executors.newSingleThreadExecutor();
        try {
            doAnswer(call -> {
                var rows=sqlSession.selectList("mapper.DriverScoreMapper.ranking", Map.of(
                        "fleetId", fleet, "period", call.<Object>getArgument(1),
                        "offset", call.<Object>getArgument(2), "limit", call.<Object>getArgument(3)));
                var write=pool.submit(() -> user("score-concurrent-"+UUID.randomUUID(),"DRIVER"));
                try { write.get(10,TimeUnit.SECONDS); } finally { write.cancel(true); }
                return rows;
            }).when(scoreMapper).ranking(eq(fleet),any(),anyLong(),anyInt());
            mvc.perform(get("/api/scores/ranking?month=2026-01&fleetId="+fleet).header("Authorization",adminToken))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.list",hasSize(2)))
                    .andExpect(jsonPath("$.data.total").value(2));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE fleet_id=? AND role='DRIVER'",Long.class,fleet)).isEqualTo(3L);
        } finally { pool.shutdownNow(); }
    }

    private org.springframework.test.web.servlet.ResultActions summary(long id,String token) throws Exception {
        return mvc.perform(get("/api/scores/drivers/"+id+"?month=2026-01").header("Authorization",token));
    }
    private org.springframework.test.web.servlet.ResultActions review(long id,String status) throws Exception {
        return mvc.perform(post("/api/alerts/"+id+"/reviews").header("Authorization",adminToken)
                .contentType("application/json").content("{\"status\":\""+status+"\",\"reason\":\"评分测试复核\"}"));
    }
    private org.springframework.test.web.servlet.ResultActions decide(long id,String status) throws Exception {
        return mvc.perform(post("/api/appeals/"+id+"/decision").header("Authorization",adminToken)
                .contentType("application/json").content("{\"status\":\""+status+"\",\"reason\":\"评分测试申诉复核\"}"));
    }
    private long appeal(long alert) throws Exception {
        String body=mvc.perform(post("/api/appeals").header("Authorization",driverToken).contentType("application/json")
                .content("{\"targetType\":\"ALERT\",\"targetId\":"+alert+",\"reason\":\"请求复核\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data").path("id").asLong();
    }
    private long user(String name,String role) {
        return insert("INSERT INTO sys_user(username,password,name,role,fleet_id) VALUES(?,'test-only',?,?,?)",name,"测试司机",role,fleet);
    }
    private String token(long id) {
        return "Bearer "+jwt.generateToken(id, 0);
    }
    private long recognition(long owner,String time) {
        return insert("INSERT INTO recognition_record(source_key,source_frame_id,vehicle_id,driver_id,fleet_id,captured_at,pred,behavior_code,ood,participated,decision_reason,speed_status) VALUES(?,1,?,?,?,?,2,'c2',0,1,'VALID','KNOWN')",UUID.randomUUID().toString(),vehicle,owner,fleet,time);
    }
    private long alert(long owner,String type,String status,String time) {
        long rec=recognition(owner,time);
        return insert("INSERT INTO alert_record(recognition_id,vehicle_id,driver_id,fleet_id,captured_at,record_type,status,sms_status) VALUES(?,?,?,?,?,?,?,'SIMULATED')",rec,vehicle,owner,fleet,time,type,status);
    }
    private long insert(String sql,Object...args) {
        var key=new GeneratedKeyHolder();
        jdbc.update(c -> { var p=c.prepareStatement(sql,new String[]{"id"}); for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);
        return key.getKey().longValue();
    }
}
