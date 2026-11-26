package video;

import entity.Camera;
import inferworker.InferFrame;
import inferworker.InferFrameQueue;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegManager {
    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    private static final String MANAGED_PREFIX = "managed:";

    private final InferFrameQueue frameQueue;
    private final VideoCaptureProperties props;
    private final Map<Long, Capture> captures = new HashMap<>();
    private final Map<Long, Process> terminating = new HashMap<>();
    private final AtomicLong frameIds = new AtomicLong();
    private ScheduledExecutorService monitor;
    private boolean closed;

    public void start(Camera camera) {
        start(camera, null, null);
    }

    public void startManaged(Camera camera, Supplier<Camera> policy, LongFunction<Long> currentDriver) {
        start(camera, policy, currentDriver);
    }

    private synchronized void start(Camera camera, Supplier<Camera> policy, LongFunction<Long> currentDriver) {
        if (closed) { return; }
        if (monitor == null) {
            monitor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "ffmpeg-monitor");
                thread.setDaemon(true);
                return thread;
            });
            monitor.scheduleWithFixedDelay(this::check, 100, 100, TimeUnit.MILLISECONDS);
        }
        Capture previous = captures.get(camera.getId());
        if (previous != null && !sameConfig(previous.camera, camera)) { stop(camera.getId()); }
        Capture capture = captures.computeIfAbsent(camera.getId(), id -> new Capture(camera, policy, currentDriver));
        if (capture.process == null || !capture.process.isAlive()) {
            retire(camera.getId(), capture);
            launch(capture);
        }
    }

    public synchronized void stop(long cameraId) {
        Capture capture = captures.remove(cameraId);
        if (capture != null) { retire(cameraId, capture); }
    }

    public synchronized void restart(Camera camera) {
        stop(camera.getId());
        start(camera);
    }

    public static boolean isManagedStream(String streamId) {
        return streamId != null && streamId.startsWith(MANAGED_PREFIX);
    }

    public synchronized boolean isCurrentStream(long cameraId, String streamId) {
        Capture capture = captures.get(cameraId);
        return capture != null && capture.process != null && streamId != null && streamId.equals(capture.streamId);
    }

    public record Status(String state, Instant lastFrameAt, String failureReason) { }

    public synchronized Status status(long cameraId) {
        Capture capture = captures.get(cameraId);
        if (capture == null) { return new Status("STOPPED", null, null); }
        if (capture.process == null) {
            String state = "START_FAILED".equals(capture.failureReason) ? "FAILED" : "RETRYING";
            return new Status(state, capture.lastSuccessfulFrame, capture.failureReason);
        }
        return new Status(capture.receivedFrame ? "CAPTURING" : "CONNECTING", capture.lastSuccessfulFrame, null);
    }

    @PreDestroy
    public synchronized void stopAll() {
        closed = true;
        if (monitor != null) { monitor.shutdownNow(); }
        captures.forEach(this::retire);
        captures.clear();
        terminating.values().forEach(Process::destroyForcibly);
        terminating.clear();
    }

    private synchronized void check() {
        if (closed) { return; }
        long now = System.nanoTime();
        terminating.values().removeIf(process -> !process.isAlive());
        for (Capture capture : captures.values()) {
            if (capture.process != null && (!capture.process.isAlive()
                    || now - capture.lastFrameAt >= silenceTimeout(capture.camera))) {
                log.warn("摄像头读流停止或超时，准备重连，cameraId={}", capture.camera.getId());
                capture.failureReason = capture.process.isAlive() ? "NO_FRAMES" : "STREAM_ENDED";
                retire(capture.camera.getId(), capture);
                scheduleRetry(capture);
            }
            if (capture.process == null && now - capture.retryAt >= 0) { launch(capture); }
        }
    }

    private void launch(Capture capture) {
        Long id = capture.camera.getId();
        Process previous = terminating.get(id);
        if (previous != null && previous.isAlive()) {
            scheduleRetry(capture);
            return;
        }
        try {
            if (capture.policy != null && !sameConfig(capture.camera, capture.policy.get())) {
                capture.failureReason = "CONFIG_BLOCKED";
                scheduleRetry(capture);
                return;
            }
            Process process = startProcess(capture.camera);
            capture.process = process;
            capture.lastFrameAt = System.nanoTime();
            String streamId = (capture.policy == null ? "" : MANAGED_PREFIX) + UUID.randomUUID();
            capture.streamId = streamId;
            capture.receivedFrame = false;
            Thread reader = new Thread(() -> readFrames(capture, process, streamId), "ffmpeg-" + id);
            reader.setDaemon(true);
            reader.start();
            log.info("FFmpeg 已启动，cameraId={}", id);
        } catch (IOException | RuntimeException ex) {
            capture.failureReason = "START_FAILED";
            log.warn("FFmpeg 启动失败，cameraId={}，异常类型={}", id, ex.getClass().getSimpleName());
            scheduleRetry(capture);
        }
    }

    private void retire(long id, Capture capture) {
        Process process = capture.process;
        capture.process = null;
        capture.streamId = null;
        if (process == null) { return; }
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
            terminating.put(id, process);
        }
    }

    private void scheduleRetry(Capture capture) {
        long initial = props.getReconnectInitialDelayMs();
        long max = Math.max(initial, props.getReconnectMaxDelayMs());
        capture.delayMs = capture.delayMs == 0 ? initial : Math.min(max, capture.delayMs * 2);
        capture.retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(capture.delayMs);
    }

    private long silenceTimeout(Camera camera) {
        return Math.max(TimeUnit.MILLISECONDS.toNanos(props.getNoFrameTimeoutMs()),
                (long) (sampleInterval(camera) * 3 * TimeUnit.SECONDS.toNanos(1)));
    }

    private double sampleInterval(Camera camera) {
        return camera.getSampleIntervalSeconds() == null ? 2.0 : camera.getSampleIntervalSeconds();
    }

    Process startProcess(Camera camera) throws IOException {
        var args = new ArrayList<>(List.of(
                props.getFfmpegPath(), "-fflags", "nobuffer", "-flags", "low_delay"));
        if (camera.getVehicleId() != null) {
            args.addAll(List.of("-protocol_whitelist", "rtsp,rtp,tcp,udp,http,https,tls,crypto"));
        }
        args.addAll(List.of("-i", camera.getStreamUrl(), "-vf", "fps=" + (1.0 / sampleInterval(camera)),
                "-f", "image2pipe", "-vcodec", "mjpeg", "-q:v", "2", "-"));
        var builder = new ProcessBuilder(args);
        return builder.redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private void readFrames(Capture capture, Process process, String streamId) {
        try (var input = new BufferedInputStream(process.getInputStream())) {
            var frame = new ByteArrayOutputStream();
            boolean inside = false;
            int previous = -1;
            for (int current; (current = input.read()) != -1; previous = current) {
                if (!inside) {
                    if (previous == 0xff && current == 0xd8) {
                        frame.write(previous);
                        frame.write(current);
                        inside = true;
                    }
                    continue;
                }
                frame.write(current);
                if (frame.size() > MAX_FRAME_BYTES) {
                    throw new IOException("JPEG 超过大小限制");
                }
                if (previous == 0xff && current == 0xd9) {
                    if (!submit(capture, process, streamId, frame.toByteArray())) { return; }
                    frame.reset();
                    inside = false;
                }
            }
        } catch (IOException ex) {
            log.warn("摄像头读流失败，cameraId={}，异常类型={}", capture.camera.getId(), ex.getClass().getSimpleName());
        } finally {
            readerStopped(capture, process);
        }
    }

    private boolean submit(Capture capture, Process process, String streamId, byte[] jpeg) {
        Camera camera = capture.camera;
        long capturedAt = System.currentTimeMillis();
        Long driver = camera.getDriverId();
        if (capture.policy != null) {
            try {
                if (!sameConfig(camera, capture.policy.get())) { return false; }
                driver = capture.currentDriver.apply(camera.getVehicleId());
            } catch (RuntimeException ex) {
                log.warn("无法核对摄像头采集配置，cameraId={}，异常类型={}", camera.getId(), ex.getClass().getSimpleName());
                return false;
            }
        }
        synchronized (this) {
            if (capture.process != process) { return false; }
            capture.lastFrameAt = System.nanoTime();
            capture.lastSuccessfulFrame = Instant.ofEpochMilli(capturedAt);
            capture.receivedFrame = true;
            capture.failureReason = null;
            capture.delayMs = 0;
            frameQueue.offer(new InferFrame(frameIds.incrementAndGet(), camera.getId(), camera.getVehicleId(),
                    driver, capturedAt, jpeg, streamId));
            return true;
        }
    }

    private static boolean sameConfig(Camera expected, Camera current) {
        return current != null && Objects.equals(expected.getVehicleId(), current.getVehicleId())
                && Objects.equals(expected.getStreamUrl(), current.getStreamUrl())
                && Objects.equals(expected.getSampleIntervalSeconds(), current.getSampleIntervalSeconds());
    }

    private synchronized void readerStopped(Capture capture, Process process) {
        if (capture.process == process) {
            capture.failureReason = "STREAM_ENDED";
            retire(capture.camera.getId(), capture);
            scheduleRetry(capture);
        }
    }

    private static final class Capture {
        private final Camera camera;
        private final Supplier<Camera> policy;
        private final LongFunction<Long> currentDriver;
        private Process process;
        private String streamId;
        private Instant lastSuccessfulFrame;
        private String failureReason;
        private boolean receivedFrame;
        private long lastFrameAt;
        private long retryAt = System.nanoTime();
        private long delayMs;

        private Capture(Camera camera, Supplier<Camera> policy, LongFunction<Long> currentDriver) {
            this.camera = camera;
            this.policy = policy;
            this.currentDriver = currentDriver;
        }
    }
}
