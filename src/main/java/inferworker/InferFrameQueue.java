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
 * 多线程共享」指的是多个生产者 + 一个消费者共享同一个队列
 */
@Component
public class InferFrameQueue {

    private final BlockingQueue<InferFrame> queue;

    public InferFrameQueue(InferWorkerProperties props) {
        /*
            普通的队列（比如 LinkedList）在多线程同时读写时会出问题，而且「队列空了还取」「队列满了还放」时你不知道该怎么办。BlockingQueue 帮你把这两件事都处理好了：
            - 队列空的时候去取，取的人可以等，直到有人放进东西；
            - 队列满的时候去放，放的人可以等，直到有人取走东西。
            这就是「阻塞」的意思——不是立刻失败，而是等条件满足。
            核心方法:
            put(e)：往队尾放一个元素，满了就等。
            take()：从队头取一个元素，空了就等。
            offer(e)：尝试放，满了立刻返回 false（不阻塞）。
            poll()：尝试取，空了立刻返回 null。
            poll(timeout, unit)：取，最多等 timeout 这么长时间，超时还没东西返回 null。
            peek()：只看队头、不取走。
        * */
        this.queue = new ArrayBlockingQueue<>(props.getQueueCapacity());
    }

    // 入队；满了就丢掉最旧的一帧再入队，尽量不丢新帧
    /*关于这里面为什么有循环：
    * 线程 A 想放 frameA，offer 失败（满了）。
    线程 A 执行 poll()，丢掉最旧的一帧，队列空出一个位置。
    就在这空档，线程 B 抢先 offer(frameB)，把位置又占了，队列再次满。
    线程 A 回去重试 offer(frameA)，又失败。
    线程 A 再 poll() 丢一帧，再重试……*/
    public boolean offer(InferFrame frame) {
        while (!queue.offer(frame)) {
            if (queue.poll() == null) {
                return false;
            }
        }
        return true;
    }

    /* 取一帧；最多等 timeout，超时返回 null。 */
    public InferFrame poll(long timeout, TimeUnit unit) throws InterruptedException {//阻塞时如果有人对这个进程发了中断信号interrupt()，就会抛出 InterruptedException 异常,我在inferworker/InferWorker.java 的 runLoop() 里 catch 了这个异常，直接结束线程
        return queue.poll(timeout, unit);
    }
}
