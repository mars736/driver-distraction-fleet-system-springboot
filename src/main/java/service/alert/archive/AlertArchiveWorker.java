package service.alert.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "alert.archive.enabled", havingValue = "true")
public class AlertArchiveWorker {
    private final AlertArchiveProperties properties;
    private final AlertArchiveQueue queue;
    private final WebHdfsArchiveClient client;
    private final ObjectMapper json;
    private ScheduledExecutorService executor;

    @PostConstruct
    void start() {
        long interval = properties.getInterval().toMillis();
        int batchSize = properties.getBatchSize();
        if (interval < 1000 || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("归档间隔至少一秒，批次大小应为 1 至 1000");
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "alert-hdfs-archive");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) { executor.shutdownNow(); }
    }

    private void tick() {
        try {
            runOnce(LocalDateTime.now(AlertArchiveQueue.ZONE));
        } catch (RuntimeException exception) {
            log.warn("告警归档任务扫描失败，异常类型={}", exception.getClass().getSimpleName());
        }
    }

    void runOnce(LocalDateTime at) {
        int size = properties.getBatchSize();
        queue.backfill(at, size);
        var lease = queue.claim(at, size);
        if (lease == null) { return; }

        try {
            var events = queue.events(lease);
            if (events.isEmpty()) { throw new IllegalStateException("归档批次没有事件"); }
            var writer = json.writerFor(ArchiveEvent.class).without(SerializationFeature.INDENT_OUTPUT);
            var lines = new StringBuilder();
            for (ArchiveEvent event : events) { lines.append(writer.writeValueAsString(event)).append('\n'); }
            client.publish(lease, lines.toString().getBytes(StandardCharsets.UTF_8));
            if (queue.complete(lease, LocalDateTime.now(AlertArchiveQueue.ZONE))) {
                log.info("告警归档成功，batchId={}，events={}", lease.batch().id(), events.size());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            String error = exception instanceof IOException && exception.getMessage() != null
                    ? exception.getMessage() : exception.getClass().getSimpleName();
            int limit = Math.min(error.length(), 255);
            queue.retry(lease, LocalDateTime.now(AlertArchiveQueue.ZONE), error.substring(0, limit));
            log.warn("告警归档等待重试，batchId={}，异常类型={}", lease.batch().id(), exception.getClass().getSimpleName());
        }
    }
}
