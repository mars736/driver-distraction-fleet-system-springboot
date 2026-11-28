package service.alert.archive;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties("alert.archive")
public class AlertArchiveProperties {
    private URI webhdfsUri;
    private String user;
    private String directory = "/fleet-safety/alert-events";
    private Map<String, String> datanodes = Map.of();
    private int datanodePort = 9864;
    private int batchSize = 250;
    private Duration interval = Duration.ofMinutes(1);
}
