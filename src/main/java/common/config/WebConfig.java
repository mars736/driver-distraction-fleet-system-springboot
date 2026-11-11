package common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Web 配置（M0 填充空壳）：静态资源映射。
 * 静态资源这里只给 upload 目录留占位，M1 的关键帧、M2 的导出件都往这个目录落。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 本地上传目录，默认项目根下的 upload（可在 application.yml 里用 file.upload-dir 覆盖） */
    @Value("${file.upload-dir:./upload}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 末尾必须带 /，否则会把目录当文件解析
        String location = uploadDir.endsWith("/") ? uploadDir : uploadDir + "/";
        Path alerts = Path.of(uploadDir).toAbsolutePath().normalize().resolve("alerts");
        registry.addResourceHandler("/upload/**")
                .addResourceLocations("file:" + location)
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource root) throws IOException {
                        // Windows 文件系统不区分大小写，不能仅依赖 URL 大小写敏感的安全匹配。
                        String normalized = resourcePath.replace('\\', '/');
                        if (normalized.equalsIgnoreCase("alerts") || normalized.regionMatches(true, 0, "alerts/", 0, 7)) {
                            return null;
                        }
                        Resource resource = super.getResource(resourcePath, root);
                        if (resource != null && resource.isFile() && Files.exists(alerts)
                                && resource.getFile().toPath().toRealPath().startsWith(alerts.toRealPath())) {
                            return null;
                        }
                        return resource;
                    }
                });
    }
}
