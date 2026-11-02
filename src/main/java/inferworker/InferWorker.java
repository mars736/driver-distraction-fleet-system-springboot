package inferworker;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 识别调度与推理模块（推理 Worker）。
 *
 * 职责（对应后端架构「六、识别调度与推理模块」）：
 *   1. 从全局推理队列取帧；
 *   2. 攒批：攒够 batchSize 帧，或超过 batchTimeoutMs 就送一次；
 *   3. 交给 InferClient 批量调用 FastAPI 推理；
 *   4. 把结果回写给监听器（OOD 判定 / 告警判定 / 识别记录）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InferWorker {

    private final InferFrameQueue queue;
    private final InferClient client;
    private final InferWorkerProperties props;

    /** 结果监听器列表，线程安全（告警判定、识别记录等模块往这里注册） */
    private final List<InferResultListener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean running = false;
    private Thread workerThread;

    @PostConstruct
    public void start() {
        running = true;
        workerThread = new Thread(this::runLoop, "infer-worker");
        workerThread.setDaemon(true);
        workerThread.start();
        log.info("推理 Worker 已启动：batchSize={}，timeout={}ms",
                props.getBatchSize(), props.getBatchTimeoutMs());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        log.info("推理 Worker 已停止");
    }

    /** 注册结果监听器（由告警判定 / 识别记录模块调用） */
    public void addListener(InferResultListener listener) {
        listeners.add(listener);
    }

    private void runLoop() {
        while (running) {
            try {
                // 1. 阻塞等第一帧（最多等一个 timeout，没帧就继续下一轮）
                InferFrame first = queue.poll(props.getBatchTimeoutMs(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }

                // 2. 攒批：攒够 batchSize 帧，或超过 timeout
                List<InferFrame> batch = new ArrayList<>();
                batch.add(first);
                long deadline = System.currentTimeMillis() + props.getBatchTimeoutMs();
                while (batch.size() < props.getBatchSize()) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) {
                        break;
                    }
                    InferFrame next = queue.poll(remain, TimeUnit.MILLISECONDS);
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }

                // 3. 批量推理
                List<InferResult> results = client.predict(batch);

                // 4. 回写结果（按下标一一对应）
                if (!listeners.isEmpty() && results.size() == batch.size()) {
                    for (InferResultListener l : listeners) {
                        l.onBatchResult(batch, results);
                    }
                } else if (results.size() != batch.size()) {
                    log.warn("推理返回条数({})与请求帧数({})不一致，跳过本轮回写",
                            results.size(), batch.size());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // FastAPI 暂时不可用等情况：别让 worker 崩掉，记日志后继续
                log.warn("推理 Worker 本轮处理失败：{}", e.getMessage());
            }
        }
    }
}
