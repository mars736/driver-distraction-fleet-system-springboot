package service.rating;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@ConfigurationProperties("driver-safety-rating")
@Getter
@Setter
public class DriverSafetyRatingProperties {
    private boolean enabled;
    private String directory = "/fleet-safety/ratings";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DriverSafetyRatingProperties.class)
    static class Registration { }
}
