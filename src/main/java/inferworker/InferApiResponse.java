package inferworker;

import lombok.Data;

import java.util.List;

/**
 * FastAPI /predict 的响应外层结构：{"results": [...]}
 */
@Data
public class InferApiResponse {
    private List<InferResult> results;
}
