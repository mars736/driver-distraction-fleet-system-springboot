package inferworker;

import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 全局带上限的推理帧队列（对应后端架构「全局带上限的推理任务队列」）。
 * 队列满时丢弃最旧帧，保证实时性、不阻塞抽帧。
 *
 * 使用方式：抽帧模块（FfmpegManager）把每帧 JPEG 塞进这里，推理 Worker 从这里取。
 */
@Component
public class InferFrameQueue {

    private final BlockingQueue<InferFrame> queue;

    public InferFrameQueue(InferWorkerProperties props) {
        this.queue = new ArrayBlockingQueue<>(props.getQueueCapacity());
    }

    /** 入队；满了就丢掉最旧的一帧再入队，尽量不丢新帧。 */
    public boolean offer(InferFrame frame) {
        while (!queue.offer(frame)) {
            if (queue.poll() == null) {
                return false;
            }
        }
        return true;
    }

    /** 取一帧；最多等 timeout，超时返回 null。 */
    public InferFrame poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }
}
