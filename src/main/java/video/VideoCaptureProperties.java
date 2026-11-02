package video;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 视频接入配置，对应 application.yml 里的 video.*
 */
@Data
@ConfigurationProperties(prefix = "video")
public class VideoCaptureProperties {

    /** ffmpeg 可执行文件的名字或完整路径 */
    private String ffmpegPath = "ffmpeg";
}
