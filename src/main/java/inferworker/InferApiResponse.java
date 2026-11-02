package inferworker;

import lombok.Data;

import java.util.List;

/**
 * FastAPI /predict 的响应外层结构：{"results": [...]}
 * 注意这里只是外层结构，不包含内层的每一帧结果。
 */
@Data
public class InferApiResponse {
    private List<InferResult> results;
}
