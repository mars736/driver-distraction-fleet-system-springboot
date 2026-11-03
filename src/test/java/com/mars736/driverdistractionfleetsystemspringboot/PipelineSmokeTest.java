package com.mars736.driverdistractionfleetsystemspringboot;

import entity.Camera;
import inferworker.InferClient;
import inferworker.InferFrame;
import inferworker.InferFrameQueue;
import inferworker.InferResult;
import inferworker.InferResultListener;
import inferworker.InferWorker;
import inferworker.InferWorkerProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import video.FfmpegManager;
import video.VideoCaptureProperties;

import java.io.File;
import java.util.List;

/**
 * 冒烟测试：不依赖 camera 模块和数据库，手动装配 video + inferworker，
 * 用 FFmpeg 生成一段测试视频当视频源，验证「抽帧 → 队列 → 攒批 → FastAPI → 结果」能跑通。
 *
 * 前置条件：
 *   1. 装了 FFmpeg，并且命令行能直接敲到 ffmpeg。
 *   2. 先启动 FastAPI：uvicorn inference:app --port 8000
 *
 * 运行方式：在 IntelliJ 里右键这个类 -> Run 'PipelineSmokeTest.main()'。
 */
public class PipelineSmokeTest {

    public static void main(String[] args) throws Exception {
        // 1. 配置（用默认值，不改）
        InferWorkerProperties inferProps = new InferWorkerProperties();
        VideoCaptureProperties videoProps = new VideoCaptureProperties();

        // 2. 手动装配（不走 Spring）
        InferFrameQueue queue = new InferFrameQueue(inferProps);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        RestTemplate restTemplate = new RestTemplate(factory);
        InferClient client = new InferClient(restTemplate, inferProps);

        InferWorker worker = new InferWorker(queue, client, inferProps);
        worker.addListener(new InferResultListener() {
            @Override
            public void onBatchResult(List<InferFrame> frames, List<InferResult> results) {
                System.out.println(">>> 收到一批结果，帧数=" + frames.size() + "，结果数=" + results.size());
                for (InferResult r : results) {
                    System.out.println("    pred=" + r.getPred()
                            + "  class=" + r.getClassName()
                            + "  max_p=" + r.getMaxP()
                            + "  energy=" + r.getEnergy()
                            + "  is_ood=" + r.isOod());
                }
            }
        });
        worker.start();
        System.out.println(">>> 推理 Worker 已启动");

        FfmpegManager ffmpeg = new FfmpegManager(queue, videoProps);

        // 3. 用 FFmpeg 生成一段测试视频（10 秒、640x480、10 帧/秒）
        System.out.println(">>> 正在生成测试视频 ...");
        String testVideo = generateTestVideo();
        System.out.println(">>> 测试视频路径: " + testVideo);

        // 4. 手动构造一个 Camera（不碰 camera 模块/数据库）
        Camera camera = new Camera();
        camera.setId(1L);
        camera.setStreamUrl(testVideo);
        camera.setSampleIntervalSeconds(1.0);   // 每秒抽一帧，10 秒约出 10 帧
        camera.setEnabled(true);

        System.out.println(">>> 启动抽帧 ...");
        ffmpeg.start(camera);

        // 5. 等它跑一会儿：抽帧 → 队列 → 攒批 → FastAPI → 结果回写
        Thread.sleep(15000);

        // 6. 收尾
        ffmpeg.stop(1L);
        worker.stop();
        System.out.println(">>> 测试结束");
    }

    /**
     * 用 FFmpeg 的 testsrc 滤镜生成一段测试视频，返回绝对路径。
     */
    private static String generateTestVideo() throws Exception {
        String out = new File("smoke_test_input.mp4").getAbsolutePath();
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=10:size=640x480:rate=10",
                "-pix_fmt", "yuv420p", "-c:v", "mpeg4",
                out
        );
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("生成测试视频失败，请确认已安装 FFmpeg 且在 PATH 中");
        }
        return out;
    }
}
