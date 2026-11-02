package inferworker;

import java.util.List;

/**
 * 推理结果监听器：供「OOD 判定 / 告警判定 / 识别记录」模块订阅。
 * 收到一批帧和对应的一批结果，两者按下标一一对应。
 */
public interface InferResultListener {

    void onBatchResult(List<InferFrame> frames, List<InferResult> results);
}
