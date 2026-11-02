package inferworker;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * 调用 FastAPI 推理子服务（/predict）的 HTTP 客户端。
 * 用 multipart/form-data 一次传一批 JPEG，返回一批结果（顺序与上传一致）。
 */
@Component
@RequiredArgsConstructor
public class InferClient {

    private final RestTemplate restTemplate;
    private final InferWorkerProperties props;

    public List<InferResult> predict(List<InferFrame> frames) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        for (InferFrame frame : frames) {
            body.add("files", new ByteArrayResource(frame.getJpeg()) {
                @Override
                public String getFilename() {
                    return "frame.jpg";
                }
            });
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<InferApiResponse> resp =
                restTemplate.postForEntity(props.getFastapiUrl(), entity, InferApiResponse.class);

        InferApiResponse api = resp.getBody();
        return api != null && api.getResults() != null ? api.getResults() : List.of();
    }
}
