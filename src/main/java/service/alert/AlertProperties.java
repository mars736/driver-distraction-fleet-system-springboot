package service.alert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "alert")
public class AlertProperties {
    @Min(1) private int smsConsecutiveFrames = 5;
    @Min(1) private int otherHourlyThreshold = 30;
    @DecimalMin("0.0") private double silentSpeedKmh = 5;
    @Min(1) private int queueCapacity = 128;
    @Min(1) private int maxAttempts = 3;
    @Min(1) private long retryDelayMs = 1000;
    @Min(1) private long shutdownWaitMs = 5000;
}
