package inferworker;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * FastAPI /predict 返回的每一帧结果。
 * 字段与 FastAPI 的 FrameResult 对应（snake_case -> camelCase）。
 */
@Data
public class InferResult {

    @JsonProperty("pred")
    private int pred;

    @JsonProperty("class_name")
    private String className;

    @JsonProperty("max_p")
    private double maxP;

    @JsonProperty("energy")
    private double energy;

    @JsonProperty("knn_dist")
    private double knnDist;

    @JsonProperty("is_ood")
    private boolean ood;
}
