package service.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import service.alert.rule.AlertRuleEngine;

@Slf4j
@Configuration
@EnableConfigurationProperties(AlertProperties.class)
public class AlertConfiguration {
    @Bean
    public AlertRuleEngine alertRuleEngine(AlertProperties properties) {
        return new AlertRuleEngine(properties.getSmsConsecutiveFrames(),
                properties.getOtherHourlyThreshold(), properties.getSilentSpeedKmh());
    }

    @Bean
    @ConditionalOnMissingBean(VehicleSpeedProvider.class)
    public VehicleSpeedProvider unknownVehicleSpeedProvider() {
        return (vehicleId, capturedAt) -> null;
    }

    @Bean
    @ConditionalOnMissingBean(SmsSender.class)
    public SmsSender loggingSmsSender() {
        return (alert, phone) -> {
            log.info("模拟短信，未实际发送：alertId={}，vehicleId={}，behavior={}",
                    alert.getId(), alert.getVehicleId(), alert.getBehaviorCode());
            return SmsSender.Delivery.SIMULATED;
        };
    }
}
