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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegManager {
    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private final InferFrameQueue frameQueue;
    private final VideoCaptureProperties props;
    private final Map<Long, Capture> captures = new HashMap<>();
    private final Map<Long, Process> terminating = new HashMap<>();
    private final AtomicLong frameIds = new AtomicLong();
    private ScheduledExecutorService monitor;
    private boolean closed;

    public synchronized void start(Camera camera) {
        if (closed) { return; }
        if (monitor == null) {
            monitor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "ffmpeg-monitor");
                thread.setDaemon(true);
                return thread;
            });
            monitor.scheduleWithFixedDelay(this::check, 100, 100, TimeUnit.MILLISECONDS);
        }
        Capture capture = captures.computeIfAbsent(camera.getId(), id -> new Capture(camera));
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
            Process process = startProcess(capture.camera);
            capture.process = process;
            capture.lastFrameAt = System.nanoTime();
            String streamId = UUID.randomUUID().toString();
            Thread reader = new Thread(() -> readFrames(capture, process, streamId), "ffmpeg-" + id);
            reader.setDaemon(true);
            reader.start();
            log.info("FFmpeg 已启动，cameraId={}", id);
        } catch (IOException ex) {
            log.warn("FFmpeg 启动失败，cameraId={}，异常类型={}", id, ex.getClass().getSimpleName());
            scheduleRetry(capture);
        }
    }

    private void retire(long id, Capture capture) {
        Process process = capture.process;
        capture.process = null;
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
        var builder = new ProcessBuilder(props.getFfmpegPath(), "-fflags", "nobuffer", "-flags", "low_delay",
                "-i", camera.getStreamUrl(), "-vf", "fps=" + (1.0 / sampleInterval(camera)),
                "-f", "image2pipe", "-vcodec", "mjpeg", "-q:v", "2", "-");
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

    private synchronized boolean submit(Capture capture, Process process, String streamId, byte[] jpeg) {
        if (capture.process != process) { return false; }
        capture.lastFrameAt = System.nanoTime();
        capture.delayMs = 0;
        Camera camera = capture.camera;
        frameQueue.offer(new InferFrame(frameIds.incrementAndGet(), camera.getId(), camera.getVehicleId(),
                camera.getDriverId(), System.currentTimeMillis(), jpeg, streamId));
        return true;
    }

    private synchronized void readerStopped(Capture capture, Process process) {
        if (capture.process == process) {
            retire(capture.camera.getId(), capture);
            scheduleRetry(capture);
        }
    }

    private static final class Capture {
        private final Camera camera;
        private Process process;
        private long lastFrameAt;
        private long retryAt = System.nanoTime();
        private long delayMs;

        private Capture(Camera camera) { this.camera = camera; }
    }
}
