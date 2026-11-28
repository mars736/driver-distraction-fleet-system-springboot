package video.evidence;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@Getter
@Setter
@ConfigurationProperties("video.evidence")
public class VideoEvidenceProperties {
    private boolean enabled;
    private Path workspace = Path.of("var", "alert-video-evidence");
    private String directory = "/fleet-safety/evidence";

    public void validate() {
        if (workspace == null || directory == null || !directory.matches("(?:/[A-Za-z0-9_-]+)+")) {
            throw new IllegalArgumentException("告警录像工作目录或 HDFS 目录无效");
        }
    }
}
