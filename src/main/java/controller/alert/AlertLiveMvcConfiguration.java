package controller.alert;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import service.alert.live.AlertLiveStream.Subscription;

@Configuration(proxyBeanMethods = false)
public class AlertLiveMvcConfiguration implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AsyncHandlerInterceptor() {
            @Override
            public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                    Object handler) {
                // MVC 已为 SseEmitter 接上响应处理器，之后的事件只经过我们的有界队列。
                if (request.getAttribute(AlertLiveController.SUBSCRIPTION) instanceof Subscription subscription) {
                    subscription.start().run();
                }
            }

            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                    Exception exception) {
                // 异步初始化之前失败时，仍要归还预留的连接名额。
                if (request.getAttribute(AlertLiveController.SUBSCRIPTION) instanceof Subscription subscription) {
                    subscription.stop().run();
                }
            }
        }).addPathPatterns(AlertLiveController.PATH);
    }
}
