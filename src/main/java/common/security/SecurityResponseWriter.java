package common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.response.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 认证和权限错误统一使用 Result，避免手工拼接 JSON。 */
@Component
@RequiredArgsConstructor
public class SecurityResponseWriter {
    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Result.error(status, message));
    }
}
