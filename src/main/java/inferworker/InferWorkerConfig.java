package inferworker;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 推理模块装配：启用配置属性、注册带超时的 RestTemplate。
 */
@Configuration//作用就是Configuration修饰的配置类，里面加上@Bean的方法返回的对象就会被注册进容器
//有一个问题：为什么不直接在RestTemplate类的头上加上@component：因为RestTemplate类在第三方jar包里，改不了源码
//还有一个问题，配置类上写@component，里面的方法加@Bean，返回的对象也会被注册，为什么不用@component：因为如果配置类里有两个bean注册方法而且都是new的相同的类，那么同一个类在容器里面就会出现两个对象，不符合单例模式，springboot在注入的时候不知道注入哪一个实例
@EnableConfigurationProperties(InferWorkerProperties.class)//让InferWorkerProperties成为一个bean，这样子也就同步开启了InferWorkerProperties的yml的对这个类的注入
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
