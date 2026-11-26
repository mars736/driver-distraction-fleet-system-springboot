package entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AppealMaterial {
    private Long id;
    private Long appealId;
    private String originalName;
    private String contentType;
    private Long sizeBytes;
    // 服务器保存位置只供后端读取，不直接返回前端；前端使用下面的鉴权下载地址。
    @JsonIgnore private String relativePath;
    private Long uploadedBy;
    private LocalDateTime uploadedAt;
    private Availability availability;

    public enum Availability { AVAILABLE, UNAVAILABLE }
    public String getDownloadUrl() { return "/api/appeals/" + appealId + "/materials/" + id; }
}
