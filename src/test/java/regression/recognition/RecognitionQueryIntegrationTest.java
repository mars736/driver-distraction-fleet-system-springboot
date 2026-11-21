package regression.recognition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import mapper.RecognitionQueryMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import service.recognition.RecognitionQueryFilter;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 MySQL 库上验证真实 SQL、JWT、权限链和申诉衔接，不依赖摄像头或推理模型。 */
@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class)
@AutoConfigureMockMvc
class RecognitionQueryIntegrationTest {
    @TempDir static Path uploads;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("file.upload-dir", () -> uploads.toString()); }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @Autowired SqlSessionTemplate sqlSession;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean RecognitionQueryMapper mapper;
    private final List<Long> records = new ArrayList<>(), alerts = new ArrayList<>(), users = new ArrayList<>();
    private long fleetA, fleetB, vehicleA, vehicleB, driverA, driverB, admin;
    private long trigger, ordinary, ood, low, normal, tied, ownerless, foreign, unbound, phoneAlert;
    private String adminToken, driverToken, otherToken, relativeImage;
    private byte[] image;

    @BeforeEach void fixtures() throws Exception {
        String key = UUID.randomUUID().toString().replace("-", "").substring(0,16);
        fleetA = insert("INSERT INTO fleet(fleet_no,name) VALUES(?,?)", "recognition-A-" + key, "识别查询车队A");
        fleetB = insert("INSERT INTO fleet(fleet_no,name) VALUES(?,?)", "recognition-B-" + key, "识别查询车队B");
        vehicleA = insert("INSERT INTO vehicle(fleet_id,plate_no) VALUES(?,?)", fleetA, "RA" + key.substring(0,10));
        vehicleB = insert("INSERT INTO vehicle(fleet_id,plate_no) VALUES(?,?)", fleetB, "RB" + key.substring(0,10));
        driverA = user("recognition-a-" + key, "DRIVER", fleetA);
        driverB = user("recognition-b-" + key, "DRIVER", fleetB);
        admin = user("recognition-admin-" + key, "PLATFORM_ADMIN", null);
        adminToken = token(admin); driverToken = token(driverA); otherToken = token(driverB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB), "jpg", bytes); image = bytes.toByteArray();
        relativeImage = "alerts/recognition-" + key + "/trigger.jpg";
        Files.createDirectories(uploads.resolve(relativeImage).getParent()); Files.write(uploads.resolve(relativeImage), image);
        trigger = record(driverA, vehicleA, fleetA, 1L, "10:00:00", "c1", false, true, "EVALUATED", relativeImage);
        ordinary = record(driverA, vehicleA, fleetA, 1L, "10:30:00", "c2", false, true, "EVALUATED", null);
        ood = record(driverA, vehicleA, fleetA, 1L, "10:40:00", null, true, false, "OOD", null);
        low = record(driverA, vehicleA, fleetA, 1L, "10:50:00", "c4", false, false, "LOW_SPEED", null);
        normal = record(driverA, vehicleA, fleetA, 2L, "11:00:00", "c0", false, true, "NORMAL", null);
        tied = record(driverA, vehicleA, fleetA, 2L, "11:00:00", "c3", false, true, "EVALUATED", null);
        ownerless = record(null, vehicleA, fleetA, null, "11:10:00", null, true, false, "OOD", null);
        foreign = record(driverB, vehicleB, fleetB, 3L, "11:20:00", "c2", false, true, "EVALUATED", null);
        unbound = record(driverA, null, null, null, "11:30:00", null, false, false, "NO_VEHICLE", null);
        phoneAlert = alert(trigger, "SMS_ALERT", "c1"); alert(trigger, "SPEED_UNKNOWN", null);
    }

    @AfterEach void cleanup() throws Exception {
        for (long fleet : List.of(fleetA,fleetB)) {
            jdbc.update("DELETE e FROM alert_appeal_event e JOIN alert_appeal p ON p.id=e.appeal_id WHERE p.fleet_id=?", fleet);
            jdbc.update("DELETE m FROM alert_appeal_material m JOIN alert_appeal p ON p.id=m.appeal_id WHERE p.fleet_id=?", fleet);
            jdbc.update("DELETE FROM alert_appeal WHERE fleet_id=?", fleet);
        }
        for (long id : alerts) { jdbc.update("DELETE FROM alert_review_record WHERE alert_id=?", id); jdbc.update("DELETE FROM alert_record WHERE id=?", id); }
        for (long id : records) { jdbc.update("DELETE FROM recognition_record WHERE id=?", id); }
        for (long id : users) { jdbc.update("DELETE FROM sys_user WHERE id=?", id); }
        for (long id : List.of(vehicleA,vehicleB)) { if(id!=0) jdbc.update("DELETE FROM vehicle WHERE id=?", id); }
        for (long id : List.of(fleetA,fleetB)) { if(id!=0) jdbc.update("DELETE FROM fleet WHERE id=?", id); }
        if (relativeImage != null) Files.deleteIfExists(uploads.resolve(relativeImage));
    }

    @Test void administratorCanCombineAllFiltersAndCountsAreNotMultipliedByAlerts() throws Exception {
        mvc.perform(get("/api/recognitions").header("Authorization", adminToken).param("fleetId", ""+fleetA)
                .param("vehicleId", ""+vehicleA).param("driverId", ""+driverA).param("cameraId", "1")
                .param("behaviorCode", "c1").param("ood", "false").param("participated", "true")
                .param("startTime", "2026-11-16T10:00:00").param("endTime", "2026-11-16T10:30:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(trigger))
                .andExpect(jsonPath("$.data.list[0].linkedAlerts", hasSize(2)));
        mvc.perform(get("/api/recognitions").header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(9));
        mvc.perform(get("/api/recognitions").header("Authorization", adminToken).param("fleetId", ""+fleetB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }

    @Test void driverCannotExpandOwnershipAndCameraAndOodFiltersWork() throws Exception {
        mvc.perform(get("/api/recognitions").header("Authorization", driverToken).param("ownerDriverId", ""+driverB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(7));
        mvc.perform(get("/api/recognitions").header("Authorization", driverToken).param("driverId", ""+driverB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/recognitions").header("Authorization", driverToken).param("vehicleId", ""+vehicleB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.list").isEmpty());
        mvc.perform(get("/api/recognitions").header("Authorization", driverToken).param("cameraId", "2"))
                .andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/recognitions").header("Authorization", driverToken).param("ood", "true").param("participated", "false"))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.list[0].id").value(ood))
                .andExpect(jsonPath("$.data.list[0].behaviorCode").isEmpty());
        mvc.perform(get("/api/recognitions").header("Authorization", driverToken).param("participated", "false"))
                .andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get("/api/recognitions").header("Authorization", otherToken))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.list[0].id").value(foreign));
    }

    @Test void orderingAndTimeRangeAreStableAndHalfOpen() throws Exception {
        mvc.perform(get("/api/recognitions").header("Authorization", driverToken).param("page", "1").param("pageSize", "3"))
                .andExpect(jsonPath("$.data.total").value(7))
                .andExpect(jsonPath("$.data.list[*].id").value(contains((int)unbound,(int)tied,(int)normal)));
        mvc.perform(get("/api/recognitions").header("Authorization", driverToken).param("page", "2").param("pageSize", "2"))
                .andExpect(jsonPath("$.data.list[*].id").value(contains((int)normal,(int)low)));
        mvc.perform(get("/api/recognitions").header("Authorization", driverToken).param("startTime", "2026-11-16T10:00:00")
                .param("endTime", "2026-11-16T11:00:00"))
                .andExpect(jsonPath("$.data.total").value(4));
        mvc.perform(get("/api/recognitions").header("Authorization", driverToken).param("page", "2147483647"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(7)).andExpect(jsonPath("$.data.list").isEmpty());
    }

    @Test void detailsShowRawMetadataWithoutPasswordsPathsOrProcessingKeys() throws Exception {
        mvc.perform(get(path(trigger)).header("Authorization", driverToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pred").value(1)).andExpect(jsonPath("$.data.maxP").value(0.98))
                .andExpect(jsonPath("$.data.energy").value(-12.5)).andExpect(jsonPath("$.data.knnDist").value(0.4))
                .andExpect(jsonPath("$.data.speedKmh").value(30.0)).andExpect(jsonPath("$.data.speedStatus").value("KNOWN"))
                .andExpect(jsonPath("$.data.driverName").value("识别查询测试司机"))
                .andExpect(jsonPath("$.data.fleetName").value("识别查询车队A"))
                .andExpect(jsonPath("$.data.keyFrameUrl").value(path(trigger)+"/key-frame"))
                .andExpect(jsonPath("$.data.keyFramePath").doesNotExist()).andExpect(jsonPath("$.data.sourceKey").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist()).andExpect(jsonPath("$.data.latestAppeals").isEmpty());
        mvc.perform(get(path(ood)).header("Authorization", driverToken)).andExpect(jsonPath("$.data.pred").value(999))
                .andExpect(jsonPath("$.data.ood").value(true)).andExpect(jsonPath("$.data.participated").value(false));
        mvc.perform(get(path(low)).header("Authorization", driverToken)).andExpect(jsonPath("$.data.decisionReason").value("LOW_SPEED"));
    }

    @Test void foreignOwnerlessAndMissingDetailsAreIndistinguishableForDriver() throws Exception {
        for (long id : List.of(foreign,ownerless,Long.MAX_VALUE)) {
            mvc.perform(get(path(id)).header("Authorization", driverToken)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404)).andExpect(jsonPath("$.message").value("识别记录不存在"));
        }
        mvc.perform(get(path(ownerless)).header("Authorization", adminToken)).andExpect(status().isOk());
        mvc.perform(get(path(unbound)).header("Authorization", driverToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicleId").isEmpty()).andExpect(jsonPath("$.data.fleetName").isEmpty());
    }

    @Test void latestOrdinaryAndAlertAppealsUseRealWorkflowWithoutDuplicatingRows() throws Exception {
        long first = appeal("RECOGNITION", ordinary);
        mvc.perform(post("/api/appeals/"+first+"/withdraw").header("Authorization", driverToken)).andExpect(status().isOk());
        long latest = appeal("RECOGNITION", ordinary);
        decide(latest);
        long alertAppeal = appeal("ALERT", phoneAlert); decide(alertAppeal);
        mvc.perform(get(path(ordinary)).header("Authorization", driverToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.behaviorCode").value("c2"))
                .andExpect(jsonPath("$.data.latestAppeals", hasSize(1))).andExpect(jsonPath("$.data.latestAppeals[0].id").value(latest))
                .andExpect(jsonPath("$.data.latestAppeals[0].targetType").value("RECOGNITION"))
                .andExpect(jsonPath("$.data.latestAppeals[0].status").value("REVOKED"));
        mvc.perform(get(path(trigger)).header("Authorization", driverToken))
                .andExpect(jsonPath("$.data.linkedAlerts", hasSize(2))).andExpect(jsonPath("$.data.linkedAlerts[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.data.latestAppeals", hasSize(1))).andExpect(jsonPath("$.data.latestAppeals[0].id").value(alertAppeal));
        mvc.perform(get("/api/recognitions").header("Authorization", driverToken))
                .andExpect(jsonPath("$.data.total").value(7)).andExpect(jsonPath("$.data.list", hasSize(7)));
    }

    @Test void keyFrameIsAuthenticatedPrivateAndHasNoBodyForHead() throws Exception {
        mvc.perform(get(path(trigger)+"/key-frame").header("Authorization", driverToken))
                .andExpect(status().isOk()).andExpect(content().bytes(image)).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(head(path(trigger)+"/key-frame").header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(content().string(""));
        mvc.perform(get(path(trigger)+"/key-frame").header("Authorization", otherToken).accept(MediaType.IMAGE_JPEG))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(404));
        mvc.perform(get(path(ordinary)+"/key-frame").header("Authorization", driverToken).accept(MediaType.IMAGE_JPEG))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        Files.delete(uploads.resolve(relativeImage));
        mvc.perform(get(path(trigger)+"/key-frame").header("Authorization", driverToken)).andExpect(status().isNotFound());
    }

    @Test void imagePathsCannotLeaveUploadRootOrReadOtherPrivateCategories() throws Exception {
        for (String bad : List.of("../outside.jpg", "appeals/other.jpg", uploads.getParent().resolve("outside.jpg").toString())) {
            jdbc.update("UPDATE recognition_record SET key_frame_path=? WHERE id=?", bad, trigger);
            mvc.perform(get(path(trigger)+"/key-frame").header("Authorization", adminToken))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        }
        mvc.perform(get("/upload/"+relativeImage).header("Authorization", driverToken)).andExpect(status().isForbidden());
        mvc.perform(get("/upload/"+relativeImage.replace("alerts/","ALERTS/")).header("Authorization", driverToken)).andExpect(status().isNotFound());
    }

    @Test void directoryLinksCannotExposeAppealMaterialsAsKeyFrames() throws Exception {
        Path materials = Files.createTempDirectory(Files.createDirectories(uploads.resolve("appeals")), "recognition-");
        Path privateImage = Files.write(materials.resolve("private.jpg"), image);
        Path alias = uploads.resolve("alerts").resolve("alias-" + UUID.randomUUID());
        try {
            // Windows junction 无需开发者模式；其他系统使用普通目录符号链接。
            if (System.getProperty("os.name").startsWith("Windows")) {
                String command = "$ErrorActionPreference='Stop'; New-Item -ItemType Junction -Path '"
                        + alias.toString().replace("'", "''") + "' -Target '"
                        + materials.toString().replace("'", "''") + "' | Out-Null";
                var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command).start();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).isZero();
            } else { Files.createSymbolicLink(alias, materials); }
            assertThat(Files.readAllBytes(alias.resolve("private.jpg"))).isEqualTo(image);
            jdbc.update("UPDATE recognition_record SET key_frame_path=? WHERE id=?",
                    uploads.relativize(alias.resolve("private.jpg")).toString(), trigger);
            mvc.perform(get(path(trigger)+"/key-frame").header("Authorization", driverToken))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        } finally {
            Files.deleteIfExists(alias);
            Files.deleteIfExists(privateImage);
            Files.deleteIfExists(materials);
        }
    }

    @Test void deletedProfileJoinsDoNotHideHistoricalRecognitionFromAdministrator() throws Exception {
        jdbc.update("DELETE FROM vehicle WHERE id=?",vehicleA); jdbc.update("DELETE FROM fleet WHERE id=?",fleetA); jdbc.update("DELETE FROM sys_user WHERE id=?",driverA);
        mvc.perform(get(path(trigger)).header("Authorization", adminToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.driverId").value(driverA)).andExpect(jsonPath("$.data.driverName").isEmpty())
                .andExpect(jsonPath("$.data.plateNo").isEmpty()).andExpect(jsonPath("$.data.fleetName").isEmpty());
    }

    @Test void oneSnapshotCoversCountRowsAlertsAndAppealsWhileAnotherTransactionCommits() throws Exception {
        var realMapper=sqlSession.getMapper(RecognitionQueryMapper.class);
        doAnswer(call -> {
            long count=realMapper.count(call.getArgument(0));
            var executor=Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    record(driverA,vehicleA,fleetA,1L,"12:00:00","c2",false,true,"EVALUATED",null);
                    jdbc.update("UPDATE alert_record SET status='REVOKED' WHERE id=?",phoneAlert);
                    insert("INSERT INTO alert_appeal(target_type,target_id,driver_id,fleet_id,vehicle_id,reason,target_status_before,status,decision_reason,reviewer_id,reviewed_at,create_time,update_time)"
                                    + " VALUES('ALERT',?,?,?,?,'并发复核','OPEN','REVOKED','复核撤销',?,NOW(3),NOW(3),NOW(3))",
                            phoneAlert,driverA,fleetA,vehicleA,admin);
                })).get(10,TimeUnit.SECONDS);
            } finally {executor.shutdownNow();}
            return count;
        }).when(mapper).count(any(RecognitionQueryFilter.class));
        var response=mvc.perform(get("/api/recognitions").header("Authorization",driverToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(7)).andExpect(jsonPath("$.data.list",hasSize(7))).andReturn();
        var row=json.readTree(response.getResponse().getContentAsString()).path("data").path("list");
        for(var item:row) if(item.path("id").asLong()==trigger) {
            assertThat(item.path("linkedAlerts").get(0).path("status").asText()).isEqualTo("OPEN");
            assertThat(item.path("latestAppeals").size()).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM alert_record WHERE id=?",String.class,phoneAlert)).isEqualTo("REVOKED");
        reset(mapper);
        mvc.perform(get("/api/recognitions").header("Authorization",driverToken)).andExpect(jsonPath("$.data.total").value(8));
        mvc.perform(get(path(trigger)).header("Authorization",driverToken)).andExpect(jsonPath("$.data.linkedAlerts[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.data.latestAppeals[0].status").value("REVOKED"));
    }

    @ParameterizedTest @ValueSource(strings={"/api/recognitions","/api/recognitions/1","/api/recognitions/1/key-frame"})
    void anonymousRequestsReturn401(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","PATCH","DELETE"})
    void writesAreForbiddenEvenForAdministrator(String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method),path(trigger)).header("Authorization",adminToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
    }
    @ParameterizedTest @CsvSource({"page,0","pageSize,101","vehicleId,-1","driverId,abc","cameraId,0","behaviorCode,c99",
            "ood,invalid","participated,invalid","startTime,2026-02-30T12:00:00","endTime,2026-11-16T12:00:00Z"})
    void invalidQueryUses400AndResult(String field,String value) throws Exception {
        mvc.perform(get("/api/recognitions").header("Authorization",adminToken).param(field,value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","abc"})
    void invalidRecordIdReturns400(String id) throws Exception {
        mvc.perform(get("/api/recognitions/"+id).header("Authorization",adminToken)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }
    @Test void reversedRangeAndUnsupportedAcceptRemainClientErrors() throws Exception {
        mvc.perform(get("/api/recognitions").header("Authorization",driverToken).param("startTime","2026-11-16T12:00:00").param("endTime","2026-11-16T10:00:00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/recognitions").header("Authorization",driverToken).accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andExpect(jsonPath("$.code").value(406));
    }
    @Test void tokenVersionDisabledAndUnknownRolesCannotRead() throws Exception {
        jdbc.update("UPDATE sys_user SET token_version=1 WHERE id=?",driverA);
        mvc.perform(get(path(trigger)).header("Authorization",driverToken)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE sys_user SET token_version=0,enabled=0 WHERE id=?",driverA);
        mvc.perform(get(path(trigger)).header("Authorization",driverToken)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE sys_user SET enabled=1,role='UNKNOWN' WHERE id=?",driverA);
        mvc.perform(get(path(trigger)).header("Authorization",driverToken)).andExpect(status().isForbidden());
    }
    @Test void independentSecurityChainKeepsExistingRoutesWorking() throws Exception {
        mvc.perform(get("/api/alerts").header("Authorization",adminToken).param("vehicleId",""+vehicleA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/appeals").header("Authorization",driverToken)).andExpect(status().isOk());
        mvc.perform(get("/api/fleets").header("Authorization",driverToken)).andExpect(status().isForbidden());
    }

    private String path(long id){return "/api/recognitions/"+id;}
    private String token(long id){return "Bearer "+jwt.generateToken(id,0);}
    private long appeal(String type,long target)throws Exception{
        var result=mvc.perform(post("/api/appeals").header("Authorization",driverToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\""+type+"\",\"targetId\":"+target+",\"reason\":\"请求复核识别结果\"}")).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }
    private void decide(long id)throws Exception{
        mvc.perform(post("/api/appeals/"+id+"/decision").header("Authorization",adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"REVOKED\",\"reason\":\"复核撤销\"}")).andExpect(status().isOk());
    }
    private long user(String username,String role,Long fleet){
        long id=insert("INSERT INTO sys_user(username,password,name,fleet_id,role) VALUES(?,?,?,?,?)",username,"fixture-no-login","识别查询测试司机",fleet,role);users.add(id);return id;
    }
    private long record(Long driver,Long vehicle,Long fleet,Long camera,String clock,String behavior,boolean isOod,boolean participated,String reason,String keyFrame){
        long id=insert("INSERT INTO recognition_record(source_key,source_frame_id,camera_id,vehicle_id,driver_id,fleet_id,captured_at,pred,behavior_code,max_p,energy,knn_dist,ood,participated,decision_reason,speed_kmh,speed_status,key_frame_path)"
                        +" VALUES(?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),camera,vehicle,driver,fleet,LocalDateTime.parse("2026-11-16T"+clock),
                behavior==null?999:Integer.parseInt(behavior.substring(1)),behavior,0.98,-12.5,0.4,isOod,participated,reason,30.0,"KNOWN",keyFrame);
        records.add(id);return id;
    }
    private long alert(long rec,String type,String behavior){
        long id=insert("INSERT INTO alert_record(recognition_id,camera_id,vehicle_id,driver_id,fleet_id,captured_at,behavior_code,max_p,record_type,sms_status,status) VALUES(?,1,?,?,?,?,?,0.98,?,'SIMULATED','OPEN')",
                rec,vehicleA,driverA,fleetA,LocalDateTime.parse("2026-11-16T10:00:00"),behavior,type);alerts.add(id);return id;
    }
    private long insert(String sql,Object...args){
        var key=new GeneratedKeyHolder();jdbc.update(connection->{var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;},key);return key.getKey().longValue();
    }
}
