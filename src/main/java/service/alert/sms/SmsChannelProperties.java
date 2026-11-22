package service.alert.sms;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("sms")
public class SmsChannelProperties {
    public enum Mode { SIMULATED, ALIYUN }

    private Mode mode = Mode.SIMULATED;
    private final Aliyun aliyun = new Aliyun();

    @Getter
    @Setter
    public static class Aliyun {
        private String accessKeyId;
        private String accessKeySecret;
        private String signName;
        private String templateCode;
        private String outIdPrefix;
        private String behaviorParameter;
        private String alertIdParameter;
    }
}
