package service.alert.evidence;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.AlertVideoEvidenceMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import service.alert.archive.WebHdfsArchiveClient;
import video.VideoCaptureProperties;
import video.evidence.VideoEvidenceBuffer;
import video.evidence.VideoEvidenceProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static service.alert.evidence.AlertVideoEvidence.Status.*;
import static service.alert.evidence.VideoEvidenceQueue.AFTER_MS;
import static service.alert.evidence.VideoEvidenceQueue.BEFORE_MS;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "video.evidence.enabled", havingValue = "true")
public class VideoEvidenceWorker {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long CAPTURE_TIMEOUT_MS = 75_000;
    private final VideoEvidenceQueue queue;
    private final AlertVideoEvidenceMapper mapper;
    private final VideoEvidenceBuffer buffer;
    private final VideoCaptureProperties video;
    private final VideoEvidenceProperties properties;
    private final WebHdfsArchiveClient hdfs;
    private ScheduledExecutorService executor;
    private long nextPrune;

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "alert-video-evidence");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) { executor.shutdownNow(); }
    }

    private void tick() {
        var now = System.currentTimeMillis();
        try {
            runOnce(now);
        } catch (RuntimeException ex) {
            log.warn("视频证据任务扫描失败，异常类型={}", ex.getClass().getSimpleName());
        }
        if (nextPrune <= now) {
            nextPrune = now + 30_000;
            try { buffer.prune(now); }
            catch (IOException ex) { log.warn("视频缓存清理失败，异常类型={}", ex.getClass().getSimpleName()); }
        }
    }

    void runOnce(long now) {
        var work = queue.claim(now);
        if (work == null) { return; }
        var item = work.evidence();
        long alertId = item.alertId();
        Path staged = buffer.staging().resolve(alertId + ".mp4");
        try {
            long start;
            long end;
            int size;
            if (item.byteSize() == null) {
                var clip = buffer.plan(item.streamId(), item.driverId(), item.capturedAtMs(), BEFORE_MS, AFTER_MS);
                if (clip == null) {
                    if (now >= item.capturedAtMs() + CAPTURE_TIMEOUT_MS) {
                        mapper.finish(alertId, work.token(), UNAVAILABLE, "NO_MATCHING_SEGMENTS");
                    } else {
                        mapper.retry(alertId, work.token(), now + 2_000, "WAITING_FOR_SEGMENTS");
                    }
                    return;
                }
                if (clip.end() < item.capturedAtMs() + AFTER_MS && now < item.capturedAtMs() + AFTER_MS + 12_000
                        && buffer.mayExtend(item.streamId(), item.driverId(), item.capturedAtMs())) {
                    mapper.retry(alertId, work.token(), now + 2_000, "WAITING_FOR_SEGMENTS");
                    return;
                }
                size = encode(clip, staged, work.token());
                start = clip.start();
                end = clip.end();
                if (mapper.prepared(alertId, work.token(), start, end, size) == 0) { return; }
            } else {
                start = item.actualStartMs();
                end = item.actualEndMs();
                size = item.byteSize();
            }

            if (size <= 0 || size > MAX_BYTES || !Files.isRegularFile(staged) || Files.size(staged) != size) {
                mapper.finish(alertId, work.token(), UNAVAILABLE, "STAGED_VIDEO_MISSING");
                Files.deleteIfExists(staged);
                return;
            }
            hdfs.publishEvidence(properties.getDirectory(), alertId, captureDate(item.capturedAtMs()),
                    work.token(), Files.readAllBytes(staged));
            var status = start == item.capturedAtMs() - BEFORE_MS && end == item.capturedAtMs() + AFTER_MS
                    ? READY : PARTIAL;
            if (mapper.finish(alertId, work.token(), status, null) == 1) { Files.deleteIfExists(staged); }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ex) {
            long retryAt = System.currentTimeMillis();
            var latest = mapper.find(alertId);
            if (latest != null && latest.byteSize() == null && retryAt >= item.capturedAtMs() + CAPTURE_TIMEOUT_MS) {
                if (mapper.finish(alertId, work.token(), UNAVAILABLE, "CLIP_CREATION_FAILED") == 1) {
                    try { Files.deleteIfExists(staged); }
                    catch (IOException cleanup) { log.warn("视频暂存文件清理失败，alertId={}", alertId); }
                }
            } else {
                mapper.retry(alertId, work.token(), retryAt + 5_000, "VIDEO_PROCESSING_RETRY");
            }
            log.warn("视频证据处理失败，alertId={}，异常类型={}", alertId, ex.getClass().getSimpleName());
        }
    }

    private int encode(VideoEvidenceBuffer.Clip clip, Path staged, String token)
            throws IOException, InterruptedException {
        Path manifest = clip.directory().resolve("clip-" + token + ".ffconcat");
        Path temporary = buffer.staging().resolve("clip-" + token + ".mp4");
        Process process = null;
        try {
            var list = new StringBuilder("ffconcat version 1.0\n");
            for (var part : clip.parts()) { list.append("file '").append(part).append("'\n"); }
            Files.writeString(manifest, list);
            var command = List.of(video.getFfmpegPath(), "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                    "-abort_on", "empty_output", "-f", "concat", "-safe", "1", "-i",
                    manifest.toString().replace('\\', '/'), "-ss", seconds(clip.offset()),
                    "-t", seconds(clip.end() - clip.start()), "-an", "-map", "0:v:0", "-c:v", "libx264",
                    "-preset", "ultrafast", "-threads", "2", "-pix_fmt", "yuv420p", "-crf", "25", "-maxrate", "4M",
                    "-bufsize", "4M", "-movflags", "+faststart", temporary.toString().replace('\\', '/'));
            process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(25, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("FFmpeg 视频证据剪辑失败");
            }
            long size = Files.size(temporary);
            if (size <= 0 || size > MAX_BYTES) { throw new IOException("视频证据文件大小超限"); }
            Files.move(temporary, staged, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return (int) size;
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); }
            Files.deleteIfExists(manifest);
            Files.deleteIfExists(temporary);
        }
    }

    static LocalDate captureDate(long time) { return Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate(); }
    private static String seconds(long millis) { return String.format(Locale.ROOT, "%.3f", millis / 1000.0); }
}
