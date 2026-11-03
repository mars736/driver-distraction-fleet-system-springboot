package inferworker;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 推理 Worker 的配置，对应 application.yml 里的 infer.*。
 */
@Data
@ConfigurationProperties(prefix = "infer")//仅仅指定路径，不会注册成bean，也不会被注入yml里的值
public class InferWorkerProperties {

    /** 攒批帧数 */
    private int batchSize = 20;

    /** 攒批超时（毫秒） */
    private long batchTimeoutMs = 100;

    /** 全局推理队列上限 */
    private int queueCapacity = 1024;//那个线程安全的多线程的阻塞队列，当抽帧送出去的速度大于回来的速度时，队列里的元素就会一直膨胀，限制元素最大量为1024，元素满了之后就扔掉队列里最旧的一帧

    /** FastAPI /predict 地址 */
    private String fastapiUrl = "http://127.0.0.1:8000/predict";
}
