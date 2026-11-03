package video;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 视频接入配置，对应 application.yml 里的 video.*
 */
@Data
@ConfigurationProperties(prefix = "video")//负责声明："把 application.yml 中 video.* 开头的配置，映射到本类的字段上"，但是不会注册为bean
public class VideoCaptureProperties {

    /** ffmpeg 可执行文件的名字或完整路径 */
    private String ffmpegPath = "ffmpeg";
}
