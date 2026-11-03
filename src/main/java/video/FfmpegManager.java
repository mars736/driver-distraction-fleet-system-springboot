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
@Slf4j//生成一个log日志对象
@Component
@RequiredArgsConstructor
public class FfmpegManager {

    private final InferFrameQueue frameQueue;
    private final VideoCaptureProperties props;//刚刚才注册VideoCaptureProperties类为bean，所以这里可以直接注入

    /** cameraId -> 进程 */
    private final Map<Long, Process> processes = new ConcurrentHashMap<>();//key 是摄像头 id，value 是对应的 FFmpeg 进程。ConcurrentHashMap是线程安全的map

    /** 全局帧序号（自增） */
    private final AtomicLong frameIdSeq = new AtomicLong(0);//一个线程安全的长整数计数器，初始 0。incrementAndGet() 能安全地「+1 并返回新值」，用来给每帧一个唯一序号。

    /** 应用关闭时，停掉所有进程 */
    @PreDestroy
    public void stopAll() {
        for (Long id : processes.keySet()) {
            stop(id);//stop函数自定义的，在下面
        }
    }

    /** 启动一路 FFmpeg 抽帧进程 *///注意这里实际上是两个线程，一个是os线程，用来接受网络像头的流，另一个是jvm虚拟机内部的线程，用来读 stdout 的字节
    public synchronized void start(Camera camera) {//synchronized：给方法加锁，同一时刻只有一个线程能进这个方法
        if (processes.containsKey(camera.getId())) {
            return; // 已在跑的摄像头，别重复启动
        }
        ProcessBuilder pb = new ProcessBuilder(buildCommand(camera));//ProcessBuilder：Java 里「启动一个外部进程」的工具。这里把 buildCommand(camera) 拼好的命令交给它。
        pb.redirectError(ProcessBuilder.Redirect.DISCARD); // ffmpeg跟该进程一共有3个管道stdin, stdout, stderr，任意一个管道满而不被读取都会导致进程阻塞。所以直接丢掉 stderr，避免阻塞。
        try {
            Process process = pb.start();//真正启动 FFmpeg 进程，返回一个 Process 对象。
            processes.put(camera.getId(), process);//把该进程放在线程安全的统一管理

            Thread reader = new Thread(() -> readFrames(camera, process)/*这里捕获了process，将管道一一绑定*/, "ffmpeg-" + camera.getId());//这个不是上面那个线程，而是jvm虚拟机内部的线程，用来读 stdout 的字节
            reader.setDaemon(true);//设置为守护线程，当所有非守护线程退出时，守护线程也会被强制退出。
            reader.start();

            log.info("camera {} ffmpeg 已启动，地址={}", camera.getId(), camera.getStreamUrl());
        } catch (IOException e) {
            log.error("camera {} ffmpeg 启动失败：{}", camera.getId(), e.getMessage());
        }
    }

    /** 停止一路 FFmpeg 进程 */
    public synchronized void stop(long cameraId) {
        /*
        * 虽然说map的数据类型是线程安全的，但它只对原子方法保持互斥（原子方法：即一直都是被调用的方法）
        * stop（）里面有多个原子方法，仅靠map的数据类型进行对map的访问就不再安全
        *synchronized会保证某一线程在调用该方法时，为该线程上锁，其他线程访问不了该方法 */
        Process process = processes.remove(cameraId);
        if (process != null) {
            process.destroy();
            log.info("camera {} ffmpeg 已停止", cameraId);
        }
    }

    /** 重启一路 */
    public synchronized void restart(Camera camera) {
        stop(camera.getId());
        start(camera);//start（）方法实际上开了jvm和os的两个进程
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
        try (InputStream in = process.getInputStream()) {//try with resources：自动关闭流，避免资源泄露
            ByteArrayOutputStream buf = new ByteArrayOutputStream();//会自动增长的字节缓冲区,我这里用来装一张图片的字节
            int prev = -1;//上一个字节，-1表示EOF,图片结束标志，正常字节0-255
            int cur;//当前字节
            while ((cur = in.read()) != -1) {
                buf.write(cur);//写进缓冲区
                if (prev == 0xFF && cur == 0xD9) {   // 遇到 JPEG 结束标记 FF D9
                    byte[] jpeg = buf.toByteArray();//只拷贝有效部分，返回刚好大小的数组，注意这里不是转换格式，毕竟都是字节数组
                    buf.reset();//重置缓冲区，准备下一张图片
                    InferFrame frame = new InferFrame(//包装起来，扔给inferwoker里自动运行的start（）线程，因为自动运行的infer的start（）线程只接受InferFrame类型的数据
                            frameIdSeq.incrementAndGet(),//帧id自增，注意所有线程共享frameIdSeq这个对象，所以不必担心各线程的自增的帧号不一样，但是app重启会导致自增帧号归0
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
