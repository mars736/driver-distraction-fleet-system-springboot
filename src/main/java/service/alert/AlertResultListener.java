package service.alert;

import inferworker.InferFrame;
import inferworker.InferResult;
import inferworker.InferResultListener;
import inferworker.InferWorker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 推理线程只复制结果并入有界队列；数据库、文件和短信均在独立线程执行。 */
@Slf4j
@Component
public class AlertResultListener implements InferResultListener {
    private final InferWorker inferWorker;
    private final AlertDecisionService decisionService;
    private final SmsDispatcher smsDispatcher;
    private final AlertProperties properties;
    private final BlockingQueue<QueuedFrame> queue;
    private final Map<Long, AtomicLong> lossGenerations = new ConcurrentHashMap<>();
    private final Map<Long, Long> processedGenerations = new HashMap<>();
    private final Set<Long> failedVehicles = new HashSet<>();
    private final AtomicLong rejectedFrames = new AtomicLong();
    private final AtomicLong failedFrames = new AtomicLong();
    private final Object admissionLock = new Object();
    private volatile boolean accepting;
    private Thread thread;

    private record QueuedFrame(CapturedInference input, long generation) { }
    private record FrameResult(InferFrame frame, InferResult result) { }

    public AlertResultListener(InferWorker inferWorker, AlertDecisionService decisionService,
            SmsDispatcher smsDispatcher, AlertProperties properties) {
        this.inferWorker = inferWorker;
        this.decisionService = decisionService;
        this.smsDispatcher = smsDispatcher;
        this.properties = properties;
        queue = new ArrayBlockingQueue<>(properties.getQueueCapacity());
    }

    @PostConstruct
    public void start() {
        accepting = true;
        thread = new Thread(this::consume, "alert-decision-worker");
        thread.setDaemon(true);
        thread.start();
        inferWorker.addListener(this);
    }

    @Override
    public void onBatchResult(List<InferFrame> frames, List<InferResult> results) {
        if (!accepting) { return; }
        if (frames == null || results == null || frames.size() != results.size()) {
            log.error("告警回调帧数与结果数不一致，拒绝处理该批次");
            if (frames != null) { frames.forEach(this::reject); }
            return;
        }
        List<FrameResult> ordered = new ArrayList<>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            InferFrame frame = frames.get(i);
            if (frame == null) {
                reject(frame);
            } else {
                ordered.add(new FrameResult(frame, results.get(i)));
            }
        }
        ordered.sort(Comparator.comparingLong((FrameResult pair) -> pair.frame().getCapturedAt())
                .thenComparingLong(pair -> pair.frame().getFrameId()));
        for (FrameResult pair : ordered) {
            synchronized (admissionLock) {
                // 和关闭操作互斥，确保消费线程结束后不会再有新结果入队。
                if (!accepting) { return; }
                if (pair.result() == null) {
                    reject(pair.frame());
                    log.error("告警批次包含空结果，拒绝帧：frameId={}", pair.frame().getFrameId());
                    continue;
                }
                CapturedInference input = CapturedInference.copyOf(pair.frame(), pair.result());
                AtomicLong generation = input.vehicleId() == null ? null
                        : lossGenerations.computeIfAbsent(input.vehicleId(), ignored -> new AtomicLong());
                if (!queue.offer(new QueuedFrame(input, generation == null ? 0 : generation.get()))) {
                    rejectedFrames.incrementAndGet();
                    if (generation != null) { generation.incrementAndGet(); }
                    log.error("告警队列已满，拒绝帧：vehicleId={}，frameId={}，累计拒绝={}",
                            input.vehicleId(), input.frameId(), rejectedFrames.get());
                }
            }
        }
    }

    private void reject(InferFrame frame) {
        rejectedFrames.incrementAndGet();
        if (frame != null && frame.getVehicleId() != null) {
            lossGenerations.computeIfAbsent(frame.getVehicleId(), ignored -> new AtomicLong()).incrementAndGet();
        }
    }

    private void consume() {
        while (accepting || !queue.isEmpty()) {
            try {
                QueuedFrame frame = queue.poll(200, TimeUnit.MILLISECONDS);
                if (frame != null) { process(frame); }
            } catch (InterruptedException e) {
                // 关闭时唤醒 poll，继续尽力排空已接收的结果。
            }
        }
    }

    private void process(QueuedFrame frame) {
        Long vehicle = frame.input().vehicleId();
        boolean broken = vehicle != null && (failedVehicles.contains(vehicle)
                || processedGenerations.getOrDefault(vehicle, 0L) != frame.generation());
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                smsDispatcher.dispatch(decisionService.process(frame.input(), broken));
                if (vehicle != null) {
                    processedGenerations.put(vehicle, frame.generation());
                    failedVehicles.remove(vehicle);
                }
                return;
            } catch (Exception e) {
                log.error("告警处理失败：sourceKey={}，attempt={}，异常类型={}",
                        frame.input().sourceKey(), attempt, e.getClass().getSimpleName());
                if (!accepting || attempt == properties.getMaxAttempts()) { break; }
                try {
                    Thread.sleep(properties.getRetryDelayMs());
                } catch (InterruptedException interrupted) {
                    break;
                }
            }
        }
        failedFrames.incrementAndGet();
        if (vehicle != null) { failedVehicles.add(vehicle); }
        log.error("告警帧重试耗尽，未完成处理：sourceKey={}，累计失败={}",
                frame.input().sourceKey(), failedFrames.get());
    }

    @PreDestroy
    public void stop() {
        synchronized (admissionLock) {
            accepting = false;
        }
        inferWorker.removeListener(this);
        if (thread == null) { return; }
        thread.interrupt();
        try {
            thread.join(properties.getShutdownWaitMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            log.error("告警线程未在关闭期限内结束，尚有 {} 帧在队列中", queue.size());
        }
    }

    public long getRejectedFrames() { return rejectedFrames.get(); }
    public long getFailedFrames() { return failedFrames.get(); }
}
