package service.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** 本阶段只保存触发违规的当前帧；前后帧时间线属于后续模块。 */
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
        String id = UUID.fromString(input.sourceKey()).toString();
        String day = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneId.of("Asia/Shanghai"))
                .format(Instant.ofEpochMilli(input.capturedAt()));
        String relative = "alerts/" + day + "/" + id + ".jpg";
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) { throw new IOException("Invalid key frame path"); }
        Files.createDirectories(target.getParent());
        try {
            Files.write(target, jpeg);
        } catch (IOException writeFailure) {
            try { Files.deleteIfExists(target); }
            catch (IOException cleanupFailure) { writeFailure.addSuppressed(cleanupFailure); }
            throw writeFailure;
        }
        return relative;
    }

    public void delete(String relative) {
        if (relative == null) { return; }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) { return; }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.error("回滚后清理告警关键帧失败，需人工清理：{}", relative);
        }
    }
}
