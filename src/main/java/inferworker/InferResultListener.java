package inferworker;

import java.util.List;

/**
 * 我这个inferworker包的对外接口在这，凡是实现了这个接口的类都可以注册到 InferWorker 里，收到推理结果后就会回调 onBatchResult() 方法。用inferworker里的addListener（）即可注册
 * 收到一批帧和对应的一批结果，两者按下标一一对应。
 */
public interface InferResultListener {

    void onBatchResult(List<InferFrame> frames, List<InferResult> results);
}
