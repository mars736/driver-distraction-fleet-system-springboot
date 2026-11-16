package service.alert;

import common.exception.BusinessException;
import entity.AppealMaterial;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.UUID;

/** 负责材料文件；材料归属和申诉状态由 AppealService 校验。 */
@Component
@Slf4j
public class AppealMaterialStore {
    /** 这里按文件字节数限制 1 MiB，不能只相信浏览器上报的文件大小。 */
    public static final int MAX_BYTES = 1024 * 1024;
    private final Path root;
    public AppealMaterialStore(@Value("${file.upload-dir:./upload}") String uploadDir) {
        root = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public AppealMaterial save(MultipartFile file) throws IOException {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("材料写入必须位于事务内");
        }
        if (file == null || file.isEmpty()) { throw new BusinessException(400, "材料不能为空"); }
        if (file.getSize() > MAX_BYTES) { throw new BusinessException(413, "每份材料最多 1 MiB"); }
        // 最多多读 1 个字节就能判断超限，避免把任意大的上传文件一次性读入内存。
        byte[] bytes;
        try (var input = file.getInputStream()) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) { throw new BusinessException(413, "每份材料最多 1 MiB"); }
        // 看文件内容开头来选择类型，防止把 HTML 改名为 .jpg 就当成图片收下。
        // 这里只判断文件头，不代表已经解析完整图片/PDF；下载仍按附件处理。
        String extension, contentType;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff) {
            extension = ".jpg"; contentType = "image/jpeg";
        } else if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) {
            extension = ".png"; contentType = "image/png";
        } else if (bytes.length >= 5 && Arrays.equals(Arrays.copyOf(bytes, 5), new byte[]{37,80,68,70,45})) {
            extension = ".pdf"; contentType = "application/pdf";
        } else { throw new BusinessException(400, "仅接受 JPEG、PNG 或 PDF 材料"); }
        Files.createDirectories(root);
        Path realRoot = root.toRealPath();
        // 磁盘文件名由服务端生成；用户原文件名只用于展示，不参与拼接存储路径。
        String relative = "appeals/" + LocalDate.now(ZoneId.of("Asia/Shanghai")) + "/" + UUID.randomUUID() + extension;
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        if (!target.getParent().toRealPath().startsWith(realRoot)) { throw new IOException("材料目录越界"); }
        try { Files.write(target, bytes, StandardOpenOption.CREATE_NEW); }
        catch (IOException failure) { Files.deleteIfExists(target); throw failure; }
        // 数据库事务无法自动“撤销”磁盘写入。等事务结束后检查结果，
        // 如果数据库没提交成功，就把这次新建的文件也清理掉，避免留下无主材料。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try { Files.deleteIfExists(target); }
                    catch (IOException e) { log.error("申诉材料事务回滚后文件清理失败，需人工清理"); }
                }
            }
        });
        String name = file.getOriginalFilename() == null ? "material" + extension : file.getOriginalFilename().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (name.isBlank()) { name = "material" + extension; }
        if (name.length() > 255) { name = name.substring(0, 255); }
        var material = new AppealMaterial();
        material.setOriginalName(name); material.setContentType(contentType);
        material.setSizeBytes((long) bytes.length); material.setRelativePath(relative);
        return material;
    }

    public Path read(AppealMaterial material) {
        try {
            Path candidate = root.resolve(material.getRelativePath()).normalize();
            if (!candidate.startsWith(root.resolve("appeals"))) { throw new BusinessException(404, "材料不存在"); }
            // normalize 防 ../，toRealPath 再检查符号链接的实际去向，二者都要校验。
            Path actual = candidate.toRealPath();
            if (!actual.startsWith(root.toRealPath()) || !Files.isRegularFile(actual) || !Files.isReadable(actual)) {
                throw new BusinessException(404, "材料不存在");
            }
            return actual;
        } catch (IOException | InvalidPathException e) { throw new BusinessException(404, "材料不存在"); }
    }
}
