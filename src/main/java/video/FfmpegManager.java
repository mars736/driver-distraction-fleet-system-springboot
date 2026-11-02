package video;

import entity.Camera;
import inferworker.InferFrame;
import inferworker.InferFrameQueue;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 视频接入与抽帧模块（对应后端架构「五、视频接入与抽帧」）。
 *
 * 每路摄像头一个 FFmpeg 进程：FFmpeg 拉流、按 fps 抽帧、把 JPEG 写到 stdout；
 * 本类读 stdout 的字节，按 JPEG 的 FF D8 / FF D9 切出一帧帧，塞进推理队列。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegManager {

    private final InferFrameQueue frameQueue;
    private final VideoCaptureProperties props;

    /** cameraId -> 进程 */
    private final Map<Long, Process> processes = new ConcurrentHashMap<>();

    /** 全局帧序号（自增） */
    private final AtomicLong frameIdSeq = new AtomicLong(0);

    /** 应用关闭时，停掉所有进程 */
    @PreDestroy
    public void stopAll() {
        for (Long id : processes.keySet()) {
            stop(id);
        }
    }

    /** 启动一路 FFmpeg 抽帧进程 */
    public synchronized void start(Camera camera) {
        if (processes.containsKey(camera.getId())) {
            return; // 已在跑，别重复启动
        }
        ProcessBuilder pb = new ProcessBuilder(buildCommand(camera));
        pb.redirectError(ProcessBuilder.Redirect.DISCARD); // stderr 单独丢弃，不混进 stdout
        try {
            Process process = pb.start();
            processes.put(camera.getId(), process);

            Thread reader = new Thread(() -> readFrames(camera, process), "ffmpeg-" + camera.getId());
            reader.setDaemon(true);
            reader.start();

            log.info("camera {} ffmpeg 已启动，地址={}", camera.getId(), camera.getStreamUrl());
        } catch (IOException e) {
            log.error("camera {} ffmpeg 启动失败：{}", camera.getId(), e.getMessage());
        }
    }

    /** 停止一路 FFmpeg 进程 */
    public synchronized void stop(long cameraId) {
        Process process = processes.remove(cameraId);
        if (process != null) {
            process.destroy();
            log.info("camera {} ffmpeg 已停止", cameraId);
        }
    }

    /** 重启一路 */
    public synchronized void restart(Camera camera) {
        stop(camera.getId());
        start(camera);
    }

    /** 拼 FFmpeg 命令：拉流 -> fps 抽帧 -> 输出 JPEG 到 stdout */
    private List<String> buildCommand(Camera camera) {
        double interval = camera.getSampleIntervalSeconds() != null ? camera.getSampleIntervalSeconds() : 2.0;
        double fps = 1.0 / interval;
        return List.of(
                props.getFfmpegPath(),
                "-fflags", "nobuffer", "-flags", "low_delay",
                "-i", camera.getStreamUrl(),
                "-vf", "fps=" + fps,
                "-f", "image2pipe", "-vcodec", "mjpeg", "-q:v", "2", "-"
        );
    }

    /** 读 stdout，按 FF D8 / FF D9 切 JPEG，塞进推理队列 */
    private void readFrames(Camera camera, Process process) {
        try (InputStream in = process.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int prev = -1;
            int cur;
            while ((cur = in.read()) != -1) {
                buf.write(cur);
                if (prev == 0xFF && cur == 0xD9) {   // 遇到 JPEG 结束标记 FF D9
                    byte[] jpeg = buf.toByteArray();
                    buf.reset();
                    InferFrame frame = new InferFrame(
                            frameIdSeq.incrementAndGet(),
                            camera.getId(),
                            camera.getVehicleId(),
                            camera.getDriverId(),
                            System.currentTimeMillis(),
                            jpeg);
                    frameQueue.offer(frame);          // 塞进推理队列，worker 会来取
                }
                prev = cur;
            }
        } catch (IOException e) {
            log.warn("camera {} 读流结束：{}", camera.getId(), e.getMessage());
        }
    }
}
