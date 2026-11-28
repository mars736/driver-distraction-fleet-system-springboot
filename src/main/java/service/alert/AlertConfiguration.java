package service.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import service.alert.archive.AlertArchiveProperties;
import service.alert.rule.AlertRuleEngine;
import service.alert.sms.SmsChannelProperties;

@Slf4j
@Configuration
@EnableConfigurationProperties({AlertProperties.class, SmsChannelProperties.class, AlertArchiveProperties.class})
public class AlertConfiguration {
    @Bean
    public AlertRuleEngine alertRuleEngine(AlertProperties properties) {
        return new AlertRuleEngine(properties.getSmsConsecutiveFrames(),
                properties.getOtherHourlyThreshold(), properties.getSilentSpeedKmh());
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
