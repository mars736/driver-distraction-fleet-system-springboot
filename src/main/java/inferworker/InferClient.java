package inferworker;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * 调用 FastAPI 推理子服务（/predict）的 HTTP 客户端。
 * 用 multipart/form-data 一次传一批 JPEG，返回一批结果（顺序与上传一致）。
 */
@Component
@RequiredArgsConstructor//会为类里所有 final 且没有在声明处直接赋值的字段（以及标了 @NonNull 的字段）生成一个带这些参数的构造方法。
//Spring 自己创建这个对象时，看到构造器里面有参数，就会去 Spring 容器里找有没有对应类型的 Bean，找到就传进去，没有就报错。注意哈，会报错
public class InferClient {

    private final RestTemplate restTemplate;//HTTP 客户端类
    private final InferWorkerProperties props;

    public List<InferResult> predict(List<InferFrame> frames) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();//这个map一个key可以对应多个value，存请求体
        for (InferFrame frame : frames) {
            /*
            * */
            body.add("files", new ByteArrayResource(frame.getJpeg()) {//new ByteArrayResource（）匿名内部类
                @Override
                public String getFilename() {
                    return "frame.jpg";
                }
            });
        }

        HttpHeaders headers = new HttpHeaders();//新建请求头对象
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);//往请求头里添加键值对
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);//把 body + headers 打包成一个 HttpEntity

        ResponseEntity<InferApiResponse> resp =
                restTemplate.postForEntity(props.getFastapiUrl(), entity, InferApiResponse.class);//getFastapiUrl()获取fastapi映射地址，entity是请求信息，InferApiResponse.class是期望的响应类型，返回一个 ResponseEntity 对象

        InferApiResponse api = resp.getBody();//获取响应体，即推理结果，即results数组
        return api != null && api.getResults() != null ? api.getResults() : List.of();//如果响应体不为空且结果不为空，就返回结果，否则返回空列表。这是一个三元运算符。
    }
}
