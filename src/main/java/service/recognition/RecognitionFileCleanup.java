package service.recognition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.RecognitionDeletionMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import service.alert.KeyFrameStore;

import java.io.IOException;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "recognition.cleanup.enabled", matchIfMissing = true)
public class RecognitionFileCleanup {
    private final RecognitionDeletionMapper jobs;
    private final KeyFrameStore files;
    private final TransactionTemplate transactions;

    @Scheduled(fixedDelayString = "${recognition.cleanup.interval-ms:5000}",
            initialDelayString = "${recognition.cleanup.interval-ms:5000}")
    public void run() {
        for (long id : jobs.due()) {
            transactions.executeWithoutResult(ignored -> {
                String path = jobs.lock(id);
                if (path == null) { return; }
                try {
                    if (!jobs.fileIsReferenced(path)) { files.deleteManaged(path); }
                    jobs.complete(id);
                } catch (IllegalArgumentException ex) {
                    jobs.complete(id);
                    log.warn("跳过非受管的关键帧路径，cleanupId={}", id);
                } catch (IOException ex) {
                    jobs.reschedule(id);
                    log.warn("关键帧清理待重试，cleanupId={}，异常类型={}", id, ex.getClass().getSimpleName());
                }
            });
        }
    }
}
