package controller.alert;

import common.response.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import service.alert.AlertProperties;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@RestController
public class AlertRuleConfigurationController {
    private final RuleConfiguration configuration;

    public AlertRuleConfigurationController(AlertProperties properties) {
        int consecutiveFrames = properties.getSmsConsecutiveFrames();
        int hourlyCount = properties.getOtherHourlyThreshold();
        double silentSpeed = properties.getSilentSpeedKmh();
        String identity = "1:" + consecutiveFrames + ":" + hourlyCount + ":" + silentSpeed;
        String version = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        configuration = new RuleConfiguration(version, "GLOBAL", "SPRING_BOOT_STARTUP", Instant.now(),
                new Threshold(consecutiveFrames, "FRAMES", "GTE"),
                new Threshold(hourlyCount, "EVENTS_PER_HOUR", "GT"),
                new Threshold(silentSpeed, "KM_PER_HOUR", "LTE"));
    }

    @GetMapping("/api/alerts/rule-configuration")
    public Result<RuleConfiguration> effective() {
        return Result.success(configuration);
    }

    public record RuleConfiguration(String version, String scope, String source, Instant effectiveSince,
                                    Threshold smsConsecutiveFrames, Threshold otherHourlyThreshold,
                                    Threshold silentSpeedKmh) { }

    public record Threshold(Number value, String unit, String operator) { }
}
