package inferworker;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 推理模块装配：启用配置属性、注册带超时的 RestTemplate。
 */
@Configuration
@EnableConfigurationProperties(InferWorkerProperties.class)//启用上面的配置类，让InferWorkerProperties成为一个bean。
public class InferWorkerConfig {

    @Bean//这个方法返回的 RestTemplate 也会进容器，别人就能注入了。在InferClient中刚刚就注入了一个 RestTemplate。
    public RestTemplate restTemplate() {
        // 工厂设计模式
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);   // 连接超时 3 秒，3秒连不上fastapi就报错
        factory.setReadTimeout(10000);     // 读取超时 10 秒，10秒pytorch没有返回结果就报错
        return new RestTemplate(factory);
    }

}
