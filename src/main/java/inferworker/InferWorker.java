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
 * 职责（对应后端架构「识别调度与推理模块」）：
 *   1. 从全局推理队列取帧；
 *   2. 攒批：攒够 batchSize 帧，或超过 batchTimeoutMs 就送一次；
 *   3. 交给 InferClient 批量调用 FastAPI 推理；
 *   4. 把结果回写给监听器（OOD 判定 / 告警判定 / 识别记录）。
 */
@Slf4j
@Component//写这个是因为毕竟要被后台线程调用，到时候直接注入就行
@RequiredArgsConstructor
public class InferWorker {

    private final InferFrameQueue queue;
    private final InferClient client;
    private final InferWorkerProperties props;//在inferworkerconfig里注入了这个类

    /** 结果监听器列表，线程安全（告警判定、识别记录等模块往这里注册） */
    private final List<InferResultListener> listeners = new CopyOnWriteArrayList<>();//一个「线程安全」的列表

    private volatile boolean running = false;//控制线程要不要继续跑，volatile就是说可以同步更新线程之间running的值
    private Thread workerThread;//要工作的线程对象

    @PostConstruct
    public void start() {
        running = true;
        workerThread = new Thread(this::runLoop, "infer-worker");//创建一个新线程，让它去执行 runLoop 方法，线程名取 "infer-worker"
        workerThread.setDaemon(true);//设置为守护线程，主线程结束时，守护线程也会结束，防止程序无法退出
        workerThread.start();//启动线程，开始执行 runLoop 方法
        log.info("推理 Worker 已启动：batchSize={}，timeout={}ms（攒批超过超时时间后就送一次）",
                props.getBatchSize(), props.getBatchTimeoutMs());
    }

    @PreDestroy//整个springboot应用关闭时，Spring 自动调用一次。
    public void stop() {
        running = false;//让 runLoop 的 while(running) 循环退出
        if (workerThread != null) {//万一卡在了 poll() 阻塞里，直接 interrupt() 让它抛出 InterruptedException 提前退出
            workerThread.interrupt();
        }
        log.info("推理 Worker 已停止");
    }

    /** 注册结果监听器（由告警判定 / 识别记录模块调用） */
    public void addListener(InferResultListener listener) {//实现了InferResultListener的类都是这个包之外的类
        listeners.add(listener);
    }

    private void runLoop() {
        while (running) {//检查一下running常量
            try {
                // 1. 阻塞等第一帧（最多等一个 timeout，没帧就继续下一轮）
                InferFrame first = queue.poll(props.getBatchTimeoutMs(), TimeUnit.MILLISECONDS);//从队列里取一个元素，最多等 100 毫秒。等到了就返回那一帧；等不到就返回 null。
                if (first == null) {
                    continue;//如果 100ms 内队列是空的（first 是 null），就 continue——跳过本轮循环剩下的代码，回到 while(running) 重新开始等。
                }

                // 2. 攒批：攒够 batchSize 帧，或超过 timeout
                List<InferFrame> batch = new ArrayList<>();
                batch.add(first);
                long deadline = System.currentTimeMillis() + props.getBatchTimeoutMs();//deadline是指当前时间加上超时时间batchTimeoutMs，超过这个时间不管队列里有没有20张，都送一次，System.currentTimeMillis()是从 1970-01-01 到现在的毫秒数
                while (batch.size() < props.getBatchSize()) {//只要 batch 里的帧数还少于 20，就继续攒
                    long remain = deadline - System.currentTimeMillis();//remain是指现在还剩下多少时间可以等
                    if (remain <= 0) {
                        break;//超时直接提交batch，不管队列里有没有20张，都送一次
                    }
                    InferFrame next = queue.poll(remain, TimeUnit.MILLISECONDS);//再等 remain 毫秒取一帧
                    if (next == null) {
                        break;//发现等的时间没取到帧，就 break，提交当前的 batch
                    }
                    batch.add(next);//利用剩下的remain时间等到了一帧，就加到 batch 里
                }

                // 3. 批量推理
                List<InferResult> results = client.predict(batch);

                // 4. 回写结果（按下标一一对应）
                if (!listeners.isEmpty() && results.size() == batch.size()) {//这个if里面是在看有没有监听者关心这一批的情况，发出去的张数和返回的条数是否一样，全都为是才进入分支
                    for (InferResultListener l : listeners) {
                        l.onBatchResult(batch, results);
                    }
                } else if (results.size() != batch.size()) {
                    log.warn("推理返回条数({})与请求帧数({})不一致，跳过本轮回写",
                            results.size(), batch.size());
                }

            } catch (InterruptedException e) {//当 @PreDestroy的stop() 调了 workerThread.interrupt()，正卡在 queue.poll(...) 里的线程会立刻抛 InterruptedException，进这个 catch。
                Thread.currentThread().interrupt();//Thread.currentThread().interrupt()：把「中断标记」再设回去（Java 的好习惯，告诉调用链「这个线程是被打断的」）。
                break;//跳出 while(running)，线程结束。
            } catch (Exception e) {
                // FastAPI 暂时不可用等情况：别让 worker 崩掉，记日志后继续
                log.warn("推理 Worker 本轮处理失败：{}", e.getMessage());
            }
        }
    }
}
