package common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 短信通道专用的 RestTemplate（M0 新增）。
 * 刻意不复用 inferworker 里那个 RestTemplate：那边连 FastAPI 用的读超时是 10 秒，
 * 放到短信上会把接口拖死；这里单独一套更短的超时。
 * 本轮只建 Bean，不接短信 SDK（短信发送器留到 M1/M2 的 SmsSender）。
 */
@Configuration
public class SmsRestTemplateConfig {

    /** 短信服务 Bean 名，注入时用 @Qualifier("smsRestTemplate") */
    public static final String SMS_REST_TEMPLATE = "smsRestTemplate";

    @Bean(SMS_REST_TEMPLATE)
    public RestTemplate smsRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);   // 连接超时 3 秒
        factory.setReadTimeout(5000);      // 读超时 5 秒（短信接口不该等太久）
        return new RestTemplate(factory);
    }
}
