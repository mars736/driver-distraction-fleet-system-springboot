package inferworker;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * FastAPI /predict 返回的每一帧结果。
 * 字段与 FastAPI 的 FrameResult 对应（snake_case -> camelCase）。
 * 用于接收 FastAPI 的 JSON 响应并反序列化为 Java 对象。
 * 注意这里只是内层结构，不包含外层的 JSON 数组。
 */
@Data
public class InferResult {
    //这个 @JsonProperty就是提示tomcat json里面的字段名和这里的字段的一一对应关系

    @JsonProperty("pred")
    private int pred;//预测的类别索引

    @JsonProperty("class_name")
    private String className;//预测的类别名称

    @JsonProperty("max_p")
    private double maxP;//预测的最大概率

    @JsonProperty("energy")
    private double energy;//能量

    @JsonProperty("knn_dist")
    private double knnDist;//KNN距离

    @JsonProperty("is_ood")
    private boolean ood;//是否为异常数据
}
