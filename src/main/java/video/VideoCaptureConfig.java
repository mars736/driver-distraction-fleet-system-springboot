package video;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import video.evidence.VideoEvidenceProperties;

/**
 * 视频接入模块装配：启用 video.* 配置。
 */
@Configuration
@EnableConfigurationProperties({VideoCaptureProperties.class, VideoEvidenceProperties.class})//负责注册 VideoCaptureProperties 类为 bean
public class VideoCaptureConfig {
}
