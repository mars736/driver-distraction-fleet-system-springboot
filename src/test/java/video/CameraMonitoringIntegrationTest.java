package video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import inferworker.InferClient;
import inferworker.InferFrame;
import inferworker.InferResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import service.alert.AlertDecisionService;
import service.alert.CapturedInference;
import service.camera.CameraCaptureCoordinator;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class, properties = {
        "video.approved-sources.test=rtsp://test:private-marker@camera.example/stream",
        "telemetry.offline-monitor.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@AutoConfigureMockMvc
@Timeout(25)
class CameraMonitoringIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtUtil jwt;
    @Autowired AlertDecisionService alerts;
    @Autowired CameraCaptureCoordinator coordinator;
    @MockitoSpyBean FfmpegManager ffmpeg;
    @MockitoBean InferClient inference;

    private final ConcurrentHashMap<Long, TestSource> sources = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<InferFrame> frames = new LinkedBlockingQueue<>();
    private long fleet;
    private long vehicle;
    private long firstDriver;
    private long secondDriver;
    private String admin;

    @BeforeEach
    void setup() throws Exception {
        String key = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO fleet(fleet_no,name) VALUES (?,?)", "cam-" + key, "摄像头联调");
        fleet = id("SELECT id FROM fleet WHERE fleet_no=?", "cam-" + key);
        jdbc.update("INSERT INTO vehicle(fleet_id,plate_no,monitor_enabled) VALUES (?,?,0)", fleet, "C" + key);
        vehicle = id("SELECT id FROM vehicle WHERE plate_no=?", "C" + key);
        firstDriver = driver("cam-a-" + key, vehicle);
        secondDriver = driver("cam-b-" + key, null);
        var at = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        jdbc.update("INSERT INTO driver_shift(driver_id,fleet_id,shift_date,shift_type,starts_at,ends_at) VALUES(?,?,?,'EARLY',?,?)",
                secondDriver, fleet, at.toLocalDate(), at.minusHours(1), at.plusHours(1));
        jdbc.update("INSERT INTO driver_attendance(shift_id,clock_in_at,clock_in_lat,clock_in_lon) "
                + "SELECT id,?,0,0 FROM driver_shift WHERE driver_id=?", at, secondDriver);
        long adminId = id("SELECT id FROM sys_user WHERE username='admin'");
        admin = "Bearer " + jwt.generateToken(adminId, 0);
        doAnswer(invocation -> {
            entity.Camera camera = invocation.getArgument(0);
            TestSource source = new TestSource();
            sources.put(camera.getId(), source);
            return source.process;
        }).when(ffmpeg).startProcess(any(entity.Camera.class));
        when(inference.predict(anyList())).thenAnswer(invocation -> {
            List<InferFrame> batch = invocation.getArgument(0);
            frames.addAll(batch);
            return batch.stream().map(frame -> {
                var result = new InferResult();
                result.setMaxP(0.98);
                return result;
            }).toList();
        });
    }

    @AfterEach
    void cleanup() {
        sources.keySet().forEach(ffmpeg::stop);
        jdbc.update("DELETE FROM alert_record WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM recognition_record WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM alert_vehicle_state WHERE vehicle_id=?", vehicle);
        jdbc.update("DELETE FROM camera WHERE vehicle_id=?", vehicle);
        jdbc.update("DELETE FROM driver_attendance WHERE shift_id IN (SELECT id FROM driver_shift WHERE fleet_id=?)", fleet);
        jdbc.update("DELETE FROM driver_shift WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM sys_user WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM vehicle WHERE id=?", vehicle);
        jdbc.update("DELETE FROM operation_log WHERE fleet_id=?", fleet);
        jdbc.update("DELETE FROM fleet WHERE id=?", fleet);
    }

    @Test
    void bothSwitchesGateStartupRecoveryAndBufferedFramesWhileDriverIsResolvedAtSampling() throws Exception {
        long cameraA = createCamera();
        long cameraB = createCamera();
        cameraEnabled(cameraA, true);
        cameraEnabled(cameraB, true);
        coordinator.start();
        assertThat(sources).isEmpty();

        vehicleMonitoring(1);
        TestSource first = sources.get(cameraA);
        TestSource second = sources.get(cameraB);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        InferFrame beforeSwap = frame(first);
        assertThat(beforeSwap.getDriverId()).isEqualTo(firstDriver);
        assertPersistedDriver(beforeSwap, firstDriver);
        mvc.perform(get("/api/cameras/{id}/recent-frames", cameraA).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].frameId").value(beforeSwap.getFrameId()))
                .andExpect(jsonPath("$.data[0].streamId").value(beforeSwap.getStreamId()));
        mvc.perform(get("/api/cameras/{id}/recent-frames/{frameId}", cameraA, beforeSwap.getFrameId())
                        .header("Authorization", admin).param("streamId", beforeSwap.getStreamId()))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG));
        mvc.perform(get("/api/cameras/{id}/recent-frames/{frameId}", cameraB, beforeSwap.getFrameId())
                        .header("Authorization", admin).param("streamId", beforeSwap.getStreamId()))
                .andExpect(status().isNotFound());

        bind(firstDriver, null);
        bind(secondDriver, vehicle);
        InferFrame afterSwap = frame(first);
        assertThat(afterSwap.getDriverId()).isEqualTo(secondDriver);
        assertPersistedDriver(afterSwap, secondDriver);

        // 模拟升级前已存在的重复绑定。
        jdbc.update("UPDATE sys_user SET vehicle_id=? WHERE id=?", vehicle, firstDriver);
        InferFrame ambiguous = frame(first);
        assertThat(ambiguous.getDriverId()).isNull();
        await().ignoreException(EmptyResultDataAccessException.class)
                .atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                SELECT decision_reason FROM recognition_record WHERE camera_id=? AND source_frame_id=?
                """, String.class, cameraA, ambiguous.getFrameId())).isEqualTo("DRIVER_UNRESOLVED"));

        vehicleMonitoring(0);
        assertThat(first.running.get()).isFalse();
        mvc.perform(get("/api/cameras/{id}/recent-frames/{frameId}", cameraA, beforeSwap.getFrameId())
                        .header("Authorization", admin).param("streamId", beforeSwap.getStreamId()))
                .andExpect(status().isNotFound());
        assertThat(second.running.get()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM camera WHERE vehicle_id=? AND enabled=1",
                Integer.class, vehicle)).isEqualTo(2);
        String stale = UUID.randomUUID().toString();
        alerts.process(new CapturedInference(stale, 1000, cameraA, vehicle, firstDriver,
                System.currentTimeMillis(), 1, false, 0.99, -2.0, 0.1, null, beforeSwap.getStreamId()), false);
        assertThat(id("SELECT COUNT(*) FROM recognition_record WHERE source_key=?", stale)).isZero();
        coordinator.start();
        assertThat(sources.get(cameraB)).isSameAs(second);

        cameraEnabled(cameraA, false);
        vehicleMonitoring(1);
        assertThat(sources.get(cameraA)).isSameAs(first);
        TestSource restored = sources.get(cameraB);
        assertThat(restored).isNotSameAs(second);
        mvc.perform(delete("/api/vehicles/{id}", vehicle).header("Authorization", admin))
                .andExpect(jsonPath("$.code").value(400));
        assertThat(restored.running.get()).isTrue();
        vehicleMonitoring(0);
        assertThat(restored.running.get()).isFalse();
    }

    @Test
    void cameraWritesValidateSourceAndReturnOnlySavedSafeFields() throws Exception {
        long camera = createCamera();
        mvc.perform(get("/api/cameras/{id}", camera).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceId").value("test"))
                .andExpect(jsonPath("$.data.sourceKind").value("RTSP"))
                .andExpect(jsonPath("$.data.sampleIntervalSeconds").value(2.0))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.streamUrl").doesNotExist());

        mvc.perform(put("/api/cameras/{id}", camera).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"vehicleId":%d,"sampleIntervalSeconds":1.0}
                        """.formatted(vehicle)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sourceId").value("test"));
        mvc.perform(patch("/api/cameras/{id}/sample-interval", camera).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sampleIntervalSeconds":3,"configVersion":0}
                                """)).andExpect(status().isConflict());
        mvc.perform(patch("/api/cameras/{id}/sample-interval", camera).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sampleIntervalSeconds":3,"configVersion":1}
                                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configVersion").value(2))
                .andExpect(jsonPath("$.data.vehicleId").value(vehicle))
                .andExpect(jsonPath("$.data.sourceId").value("test"))
                .andExpect(jsonPath("$.data.sampleIntervalSeconds").value(3.0));
        mvc.perform(post("/api/cameras").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceId\":\"bad\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/cameras").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sampleIntervalSeconds\":0.1}"))
                .andExpect(status().isBadRequest());
        cameraEnabled(camera, true);
        mvc.perform(put("/api/cameras/{id}", camera).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"vehicleId":%d,"sampleIntervalSeconds":4.0}
                        """.formatted(vehicle)))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/cameras/{id}", camera).header("Authorization", admin)).andExpect(status().isConflict());
        cameraEnabled(camera, false);
        cameraEnabled(camera, false);
        mvc.perform(delete("/api/cameras/{id}", camera).header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/cameras/{id}", camera).header("Authorization", admin)).andExpect(status().isNotFound());
        mvc.perform(put("/api/cameras/{id}", camera).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/cameras/{id}/enabled", camera).header("Authorization", admin).param("enabled", "false"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/cameras/{id}", camera).header("Authorization", admin)).andExpect(status().isNotFound());
    }

    private long createCamera() throws Exception {
        var result = mvc.perform(post("/api/cameras").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"vehicleId":%d,"sourceId":"test","enabled":true}
                        """.formatted(vehicle)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("id").asLong();
    }

    private void cameraEnabled(long camera, boolean enabled) throws Exception {
        mvc.perform(patch("/api/cameras/{id}/enabled", camera).header("Authorization", admin).param("enabled", "" + enabled))
                .andExpect(status().isOk());
    }

    private void vehicleMonitoring(int enabled) throws Exception {
        mvc.perform(patch("/api/vehicles/{id}/monitor-enabled", vehicle).header("Authorization", admin)
                        .param("monitorEnabled", "" + enabled))
                .andExpect(status().isOk());
    }

    private void bind(long driver, Long targetVehicle) throws Exception {
        var request = patch("/api/drivers/{id}/vehicle", driver).header("Authorization", admin);
        if (targetVehicle != null) { request.param("vehicleId", targetVehicle.toString()); }
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }

    private InferFrame frame(TestSource source) throws Exception {
        source.output.write(new byte[]{(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9});
        source.output.flush();
        var frame = frames.poll(5, TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        return frame;
    }

    private void assertPersistedDriver(InferFrame frame, long expected) {
        await().ignoreException(EmptyResultDataAccessException.class)
                .atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(id("""
                SELECT driver_id FROM recognition_record WHERE camera_id=? AND source_frame_id=?
                """, frame.getCameraId(), frame.getFrameId())).isEqualTo(expected));
    }

    private long driver(String name, Long bound) {
        jdbc.update("INSERT INTO sys_user(username,password,name,role,fleet_id,vehicle_id) VALUES (?,'unused',?,'DRIVER',?,?)",
                name, name, fleet, bound);
        return id("SELECT id FROM sys_user WHERE username=?", name);
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static final class TestSource {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Process process = mock(Process.class);
        private final PipedOutputStream output = new PipedOutputStream();

        private TestSource() throws IOException {
            var input = new PipedInputStream(output);
            when(process.getInputStream()).thenReturn(input);
            when(process.isAlive()).thenAnswer(invocation -> running.get());
            doAnswer(invocation -> {
                running.set(false);
                output.close();
                return null;
            }).when(process).destroy();
        }
    }
}
