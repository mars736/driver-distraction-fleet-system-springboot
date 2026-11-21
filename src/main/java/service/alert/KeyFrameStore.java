package service.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** 保存触发帧和告警前后帧，统一放在受保护的 alerts 目录。 */
@Slf4j
@Component
public class KeyFrameStore {
    private final Path root;

    public KeyFrameStore(@Value("${file.upload-dir:./upload}") String uploadDir) {
        root = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public String save(CapturedInference input) throws IOException {
        byte[] jpeg = input.jpeg();
        if (jpeg == null || jpeg.length == 0) { return null; }
        return saveJpeg(input.capturedAt(), jpeg);
    }

    String saveJpeg(long capturedAt, byte[] jpeg) throws IOException {
        String day = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneId.of("Asia/Shanghai"))
                .format(Instant.ofEpochMilli(capturedAt));
        String relative = "alerts/" + day + "/" + UUID.randomUUID() + ".jpg";
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root.resolve("alerts"))) { throw new IOException("Invalid key frame path"); }
        Files.createDirectories(target.getParent());
        target = checkedRealParent(target);
        Files.createFile(target);
        try {
            Files.write(target, jpeg);
        } catch (IOException writeFailure) {
            try { Files.deleteIfExists(target); }
            catch (IOException cleanupFailure) { writeFailure.addSuppressed(cleanupFailure); }
            throw writeFailure;
        }
        return relative;
    }

    void deleteOnRollback(String relative) {
        if (relative == null) { return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public int getOrder() { return Ordered.LOWEST_PRECEDENCE - 1; }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) { delete(relative); }
            }
        });
    }

    public void delete(String relative) {
        if (relative == null) { return; }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root.resolve("alerts"))) { return; }
        try {
            Files.deleteIfExists(checkedRealParent(target));
        } catch (java.nio.file.NoSuchFileException ignored) {
            // 父目录已清理，无需再删除文件。
        } catch (IOException e) {
            log.error("回滚后清理告警关键帧失败，需人工清理：{}", relative);
        }
    }

    private Path checkedRealParent(Path target) throws IOException {
        Path parent = target.getParent().toRealPath();
        if (!parent.startsWith(root.toRealPath().resolve("alerts"))) {
            throw new IOException("Invalid key frame directory");
        }
        return parent.resolve(target.getFileName());
    }
}
