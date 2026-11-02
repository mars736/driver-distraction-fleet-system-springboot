package inferworker;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 推理 Worker 的配置，对应 application.yml 里的 infer.*。
 */
@Data
@ConfigurationProperties(prefix = "infer")
public class InferWorkerProperties {

    /** 攒批帧数 */
    private int batchSize = 20;

    /** 攒批超时（毫秒） */
    private long batchTimeoutMs = 100;

    /** 全局推理队列上限 */
    private int queueCapacity = 1024;

    /** FastAPI /predict 地址 */
    private String fastapiUrl = "http://127.0.0.1:8000/predict";
}
