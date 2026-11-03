# 这个readme由ai撰写，下面这个大块（inferworker 模块使用说明（识别调度与推理）之前的文字）是我对整个inferwoker模块的调用链的说明，
# 当然由于这个模块依赖video模块和camera模块，所以也会涉及一些这两个模块的内容
# 先看看我写的简介，看不懂再往下看

项目一启动，video模块就会去看camera有没有正在用的摄像头，有点话就会拉起os和jvm里面的进程去接受帧（这是CameraService里面的功能），
以及项目一启动inferwoker的start（）方法就会开始跑，注册一个不停地从全局的InferFrameQueue里面轮询看看有没有帧进来。
这就是两个会自动跑的方法。在inferwoker的start（）方法里有帧且等条件满足了就会自动送入fastapi，
返回List<InferResult>类型的结果扔给springboot，
至此，springboot已经拿到了这一批的原传入的List<InferFrame>的帧数据以及一一对应的List<InferResult>类型的结果数据，
然后遍历监听者，运行多个l.onBatchResult(batch, results);把这两块数据全部当作参数扔给已经注册的监听器，
监听器对象就会不受控制地不断拿到正在推理的全新的帧数据以及它的识别数据

# inferworker 模块使用说明（识别调度与推理）

这个包负责把「抽帧出来的图片」变成「识别结果」，是整个违规识别链路的中转站。它不负责抽帧，也不负责告警、写库、推前端——它只做三件事：

1. 从全局队列里取一帧帧图片；
2. 攒够一批后，批量发给 FastAPI（Python 的 ResNet + OOD 推理服务）；
3. 把返回的结果，按顺序回调给所有「监听器」。

后面谁想用识别结果（比如做告警判定、写识别记录、推给前端），就实现一个监听器注册进来。

## 数据怎么流动

```
抽帧模块(FfmpegManager)
        │  往队列塞 InferFrame
        ▼
InferFrameQueue（全局队列，满 1024 就丢最旧一帧）
        │  worker 取帧
        ▼
InferWorker（攒批：满 20 帧，或 100ms 超时就送一次）
        │  调用 predict()
        ▼
InferClient（HTTP multipart 调 FastAPI /predict）
        │  返回一批 InferResult
        ▼
所有已注册的 InferResultListener（告警、记录、推前端等）
```

## 核心类一览

| 类 | 作用 |
| --- | --- |
| `InferFrame` | 一帧待推理图片，带摄像头/车辆/司机 id、时间戳和 JPEG 字节 |
| `InferResult` | FastAPI 返回的一帧结果：类别、置信度、Energy、KNN 距离、是否 OOD |
| `InferFrameQueue` | 全局有上限的阻塞队列，抽帧往里塞，worker 从里取 |
| `InferClient` | 用 `RestTemplate` 调 FastAPI 的 HTTP 客户端 |
| `InferWorker` | 核心调度：取帧、攒批、调推理、回写监听器 |
| `InferResultListener` | 对外接口，实现它就等于「我想接收识别结果」 |
| `InferWorkerProperties` | 配置类，对应 `application.yml` 里的 `infer.*` |
| `InferWorkerConfig` | 装配配置：注册带超时的 `RestTemplate` |

## 配置项（application.yml）

```yaml
infer:
  batch-size: 20        # 攒多少帧送一次 FastAPI
  batch-timeout-ms: 100 # 超过这个毫秒数还没攒满，也先送一次
  queue-capacity: 1024  # 全局队列上限，满了丢最旧帧
  fastapi-url: http://127.0.0.1:8000/predict  # FastAPI 地址
```

## 怎么使用这个模块（重点：注册监听器）

如果你想让「识别结果出来之后」做点事，就写一个类实现 `InferResultListener`，然后把自己注册到 `InferWorker` 里。

### 第 1 步：实现接口

```java
import inferworker.InferFrame;
import inferworker.InferResult;
import inferworker.InferResultListener;

import java.util.List;

public class MyAlertListener implements InferResultListener {

    @Override
    public void onBatchResult(List<InferFrame> frames, List<InferResult> results) {
        // frames 和 results 按下标一一对应
        for (int i = 0; i < frames.size(); i++) {
            InferFrame frame = frames.get(i);
            InferResult result = results.get(i);

            System.out.printf(
                "摄像头=%d 司机=%d 预测=%d(%s) 是否OOD=%s%n",
                frame.getCameraId(),
                frame.getDriverId(),
                result.getPred(),
                result.getClassName(),
                result.isOod()
            );
        }
    }
}
```

### 第 2 步：注入 InferWorker 并注册自己

```java
import inferworker.InferFrame;
import inferworker.InferResult;
import inferworker.InferResultListener;
import inferworker.InferWorker;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MyAlertListener implements InferResultListener {

    private final InferWorker worker;

    public MyAlertListener(InferWorker worker) {
        this.worker = worker;
    }

    @PostConstruct
    public void register() {
        worker.addListener(this);   // 关键一步：把自己塞进 worker 的监听器列表
    }

    @Override
    public void onBatchResult(List<InferFrame> frames, List<InferResult> results) {
        // 同上，写你的业务逻辑
    }
}
```

### 第 3 步：保证这个类能被 Spring 扫描到

`InferWorker` 是 Spring 的 `@Component`，你直接 `@Autowired` 或构造器注入它就行。但你的监听器类本身也要是 `@Component`，并且必须在 Spring 的扫描范围内。

主类里目前是这样写的：

```java
@SpringBootApplication(scanBasePackages = {"com.mars736", "common", "inferworker", "video", "camera"})
```

所以：

- 如果你的监听器放在 `com.mars736`、`common`、`inferworker`、`video`、`camera` 这些包下面，不用改任何东西。
- 如果你新建了一个包（比如 `service.alert`、`record`、`alert`），要把那个包名加进 `scanBasePackages`，否则 Spring 找不到它，`addListener` 就永远不会被调用。

## 结果字段说明

`InferResult` 里最重要的是这几个：

| 字段 | 含义 |
| --- | --- |
| `pred` | 预测的类别编号（0~9，对应 c0~c9） |
| `className` | 类别中文名，比如「右手玩手机」 |
| `maxP` | Softmax 最大置信度 |
| `energy` | Energy 分数（越小越正常，越接近 0 越可疑） |
| `knnDist` | 到最近邻的平均 L2 平方距离 |
| `ood` | 是否被判为「分布外/拒识」帧。`true` 表示这张图不是训练集那 10 类里的正常驾驶画面 |

**注意**：`ood = true` 的帧，模型给的 `pred` 和 `className` 不可信，业务上一般不要拿它做违规判定。

`InferFrame` 里带摄像头/车辆/司机 id 和时间戳，是因为后面告警、记录、推前端时都要知道「这张图是谁、哪辆车、什么时候拍的」。

## 注意事项

1. **帧和结果按下标一一对应**：`frames.get(i)` 对应 `results.get(i)`，别拿错。
2. **不注册监听器，结果会被丢弃**：`InferWorker` 里有个 `listeners` 列表，如果它是空的，推理结果回来之后没人接，就直接没了（只会在 `results.size() != batch.size()` 时打条 warn）。
3. **FastAPI 得先启动**：这个模块只是 HTTP 客户端，真正推理在 Python 那边。FastAPI 没起，`InferClient.predict()` 会抛异常，`InferWorker` 会 catch 住打日志后继续跑，不会崩。
4. **`frameId` 是内存自增的**：抽帧时在 `FfmpegManager` 里用 `AtomicLong` 现编号，Spring Boot 重启后从 0 重新开始。如果后面要持久化，别拿它当数据库主键。
5. **队列满了会丢最旧帧**：这是为了保实时性、不阻塞抽帧，是设计行为，不是 bug。

## 最小验证方式

不想起 Spring 和数据库，只想看这条链路通不通，可以跑测试目录里的 `PipelineSmokeTest.main()`，它会手动 `new` 出这些组件 + 一段 FFmpeg 测试视频，直接验证「抽帧 → 队列 → 攒批 → FastAPI → 结果」。
