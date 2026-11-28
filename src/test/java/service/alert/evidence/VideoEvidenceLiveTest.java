package service.alert.evidence;

import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import entity.Camera;
import inferworker.InferWorker;
import mapper.AlertVideoEvidenceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import service.alert.AlertDecisionService;
import service.alert.CapturedInference;
import service.alert.archive.WebHdfsArchiveClient;
import video.FfmpegManager;
import video.evidence.VideoEvidenceProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_VIDEO_LIVE_TEST", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class, properties = {
        "alert.archive.enabled=false", "telemetry.offline-monitor.enabled=false", "video.evidence.enabled=true",
        "alert.sms-consecutive-frames=1", "sms.mode=simulated"})
@AutoConfigureMockMvc
class VideoEvidenceLiveTest {
    private static final String DIRECTORY = "/fleet-safety/evidence/_acceptance/" + UUID.randomUUID();
    @TempDir static Path workspace;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry props) {
        String url = System.getenv("DRIVER_FLEET_VIDEO_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/alert_video_test(\\?.*)?")) {
            throw new IllegalArgumentException("视频验收必须使用本机隔离的 alert_video_test");
        }
        props.add("spring.datasource.url", () -> url);
        props.add("video.evidence.workspace", () -> workspace.resolve("video").toString());
        props.add("video.evidence.directory", () -> DIRECTORY);
        props.add("video.ffmpeg-path", () -> System.getenv("DRIVER_FLEET_VIDEO_FFMPEG"));
        props.add("file.upload-dir", () -> workspace.resolve("uploads").toString());
        props.add("alert.archive.webhdfs-uri", () -> System.getenv("DRIVER_FLEET_HDFS_LIVE_URI"));
        props.add("alert.archive.user", () -> System.getenv("DRIVER_FLEET_HDFS_LIVE_USER"));
        props.add("alert.archive.datanodes.node1", () -> System.getenv("DRIVER_FLEET_HDFS_LIVE_NODE1"));
        props.add("alert.archive.datanodes.node2", () -> System.getenv("DRIVER_FLEET_HDFS_LIVE_NODE2"));
        props.add("alert.archive.datanodes.node3", () -> System.getenv("DRIVER_FLEET_HDFS_LIVE_NODE3"));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired FfmpegManager recorder;
    @Autowired VideoEvidenceProperties options;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AlertDecisionService decisions;
    @Autowired InferWorker infer;
    @Autowired AlertVideoEvidenceMapper evidence;
    @MockitoSpyBean WebHdfsArchiveClient hdfs;

    @Test
    void realSrsFootageSurvivesHdfsRetryAndPlaysOnlyForTheAdministrator() throws Exception {
        var key = UUID.randomUUID().toString().substring(0, 10);
        long fleet = insert("INSERT INTO fleet(fleet_no,name) VALUES(?,?)", "clip-" + key, "视频验收");
        long vehicle = insert("INSERT INTO vehicle(fleet_id,plate_no,monitor_enabled) VALUES(?,?,1)", fleet, "VE" + key);
        long driver = user("clip-d-" + key, "DRIVER");
        long admin = user("clip-a-" + key, "PLATFORM_ADMIN");
        long cameraId = insert("""
                INSERT INTO camera(vehicle_id,stream_url,sample_interval_seconds,enabled) VALUES(?,'demo-car01',1,1)
                """, vehicle);
        var camera = new Camera();
        camera.setId(cameraId);
        camera.setVehicleId(vehicle);
        camera.setSampleIntervalSeconds(1.0);
        camera.setStreamUrl(System.getenv("DRIVER_FLEET_VIDEO_TEST_STREAM"));
        infer.stop();
        recorder.startManaged(camera, () -> camera, ignored -> driver);
        try {
            await().atMost(Duration.ofSeconds(28)).until(() -> recorder.recentPreviews(cameraId, 0).size() >= 8);
            var frame = recorder.recentPreviews(cameraId, 0).getFirst();
            var captured = Instant.ofEpochMilli(frame.capturedAt()).atZone(VideoEvidenceWorker.ZONE).toLocalDateTime();
            var result = new CapturedInference(UUID.randomUUID().toString(), frame.frameId(), cameraId, vehicle, driver,
                    frame.capturedAt(), 1, false, 0.99, -1, 0.1, frame.jpeg(), frame.streamId());
            var tx = new TransactionTemplate(transactions);
            Long rolledBack = tx.execute(status -> {
                try {
                    long id = decisions.process(result, false).getFirst().getId();
                    status.setRollbackOnly();
                    return id;
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
            assertThat(evidence.find(rolledBack)).isNull();

            doAnswer(invocation -> {
                invocation.callRealMethod();
                throw new IOException("simulate lost HDFS acknowledgement");
            }).doCallRealMethod().when(hdfs).publishEvidence(anyString(), anyLong(), any(), anyString(), any());
            long alert = decisions.process(result, false).getFirst().getId();
            assertThat(evidence.find(alert).status()).isEqualTo(AlertVideoEvidence.Status.PROCESSING);

            var created = mvc.perform(post("/api/appeals").header("Authorization", token(driver))
                            .contentType("application/json").content("""
                                    {"targetType":"ALERT","targetId":%d,"reason":"测试视频取证"}
                                    """.formatted(alert)))
                    .andExpect(status().isOk()).andReturn();
            long appeal = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(created.getResponse().getContentAsByteArray()).path("data").path("id").longValue();
            assertThat(appeal).isPositive();
            String path = "/api/appeals/" + appeal + "/evidence-video";
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", token(driver))).andExpect(status().isForbidden());
            mvc.perform(get("/api/appeals/" + appeal).header("Authorization", token(driver)))
                    .andExpect(jsonPath("$.data.videoEvidence").doesNotExist());

            try {
                await().atMost(Duration.ofSeconds(80)).until(() -> {
                    var status = evidence.find(alert).status();
                    return status != AlertVideoEvidence.Status.PROCESSING;
                });
            } catch (org.awaitility.core.ConditionTimeoutException timeout) {
                throw new AssertionError("Timed out awaiting video. " + jobState(alert), timeout);
            }
            assertThat(evidence.find(alert).status()).withFailMessage("Video state: %s", jobState(alert))
                    .isEqualTo(AlertVideoEvidence.Status.READY);
            var published = evidence.find(alert);
            assertThat(published.actualStartMs()).isEqualTo(frame.capturedAt() - 5000);
            assertThat(published.actualEndMs()).isEqualTo(frame.capturedAt() + 10000);
            assertThat(jdbc.queryForObject("SELECT attempts FROM alert_video_evidence WHERE alert_id=?", Integer.class, alert))
                    .isGreaterThanOrEqualTo(2);

            mvc.perform(get("/api/appeals/" + appeal).header("Authorization", token(admin)))
                    .andExpect(jsonPath("$.data.videoEvidence.status").value("READY"))
                    .andExpect(jsonPath("$.data.videoEvidence.playbackUrl").value(path));
            mvc.perform(head(path).header("Authorization", token(admin))).andExpect(status().isOk())
                    .andExpect(header().string("Content-Length", published.byteSize().toString()))
                    .andExpect(content().bytes(new byte[0]));
            var played = mvc.perform(get(path).header("Authorization", token(admin)))
                    .andExpect(status().isOk()).andExpect(content().contentType("video/mp4"))
                    .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
            var data = played.getContentAsByteArray();
            assertThat(data).hasSize(published.byteSize());
            assertThat(new String(data, 4, 4, StandardCharsets.US_ASCII)).isEqualTo("ftyp");
            System.out.println("Verified HDFS video: " + options.getDirectory() + "/date="
                    + captured.toLocalDate() + "/alert-" + alert + "/clip.mp4 (" + data.length + " bytes)");
        } finally {
            recorder.stop(cameraId);
        }
    }

    private Object jobState(long alert) {
        return jdbc.queryForMap("""
                SELECT status,attempts,last_error,captured_at_ms,actual_start_ms,actual_end_ms
                FROM alert_video_evidence WHERE alert_id=?
                """, alert);
    }

    private String token(long id) { return "Bearer " + jwt.generateToken(id, 0); }

    private long user(String username, String role) {
        return insert("INSERT INTO sys_user(username,password,name,role,enabled,token_version) VALUES(?,?,?, ?,1,0)",
                username, "fixture", username, role);
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
