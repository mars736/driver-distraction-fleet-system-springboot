package video;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 视频接入配置，对应 application.yml 里的 video.*
 */
@Data
@Validated
@ConfigurationProperties(prefix = "video")//负责声明："把 application.yml 中 video.* 开头的配置，映射到本类的字段上"，但是不会注册为bean
public class VideoCaptureProperties {

    /** ffmpeg 可执行文件的名字或完整路径 */
    private String ffmpegPath = "ffmpeg";
    private Map<String, String> approvedSources = new LinkedHashMap<>();

    @Min(100) @Max(300000) private long reconnectInitialDelayMs = 1000;
    @Min(100) @Max(300000) private long reconnectMaxDelayMs = 30000;
    @Min(100) @Max(600000) private long noFrameTimeoutMs = 15000;
}
