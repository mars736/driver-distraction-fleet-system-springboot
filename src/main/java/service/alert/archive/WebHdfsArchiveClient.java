package service.alert.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnExpression("${alert.archive.enabled:false} || ${video.evidence.enabled:false}")
public class WebHdfsArchiveClient {
    private static final Duration IO_TIMEOUT = Duration.ofSeconds(8);
    private static final String REST_PREFIX = "/webhdfs/v1";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(IO_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final URI nameNode;
    private final String user;
    private final String baseDirectory;
    private final Map<String, String> datanodes;
    private final int datanodePort;

    public WebHdfsArchiveClient(AlertArchiveProperties properties, ObjectMapper json) {
        this.json = json;
        nameNode = properties.getWebhdfsUri();
        user = properties.getUser();
        baseDirectory = properties.getDirectory();
        datanodePort = properties.getDatanodePort();
        if (nameNode == null || nameNode.getHost() == null
                || !("http".equals(nameNode.getScheme()) || "https".equals(nameNode.getScheme()))
                || nameNode.getPort() < 1 || nameNode.getRawUserInfo() != null || nameNode.getRawQuery() != null
                || nameNode.getRawFragment() != null
                || !("".equals(nameNode.getPath()) || "/".equals(nameNode.getPath()))) {
            throw new IllegalArgumentException("请配置 WebHDFS NameNode 的 HTTP(S) 主机和端口");
        }
        if (user == null || !user.matches("[A-Za-z0-9_.-]{1,64}")
                || baseDirectory == null || !baseDirectory.matches("(?:/[A-Za-z0-9_-]+)+")) {
            throw new IllegalArgumentException("WebHDFS 账号或归档目录无效");
        }
        if (datanodePort < 1 || datanodePort > 65535 || properties.getDatanodes().isEmpty()
                || properties.getDatanodes().entrySet().stream()
                        .anyMatch(entry -> !validHost(entry.getKey()) || !validHost(entry.getValue()))) {
            throw new IllegalArgumentException("请配置 WebHDFS DataNode 主机映射和端口");
        }
        datanodes = properties.getDatanodes().entrySet().stream().collect(Collectors.toUnmodifiableMap(
                entry -> entry.getKey().toLowerCase(Locale.ROOT),
                entry -> entry.getValue().toLowerCase(Locale.ROOT)));
    }

    public void publish(AlertArchiveQueue.Lease lease, byte[] payload) throws IOException, InterruptedException {
        var batch = lease.batch();
        String directory = baseDirectory + "/date=" + batch.partitionDate();
        publishFile(directory, "batch-" + batch.id() + ".jsonl", lease.token(), payload);
    }

    public void publishEvidence(String root, long alertId, LocalDate date, String token, byte[] payload)
            throws IOException, InterruptedException {
        publishFile(evidenceDirectory(root, alertId, date), "clip.mp4", token, payload);
    }

    public byte[] readEvidence(String root, long alertId, LocalDate date, int expectedSize)
            throws IOException, InterruptedException {
        String path = evidenceDirectory(root, alertId, date) + "/clip.mp4";
        return readFile(path, expectedSize);
    }

    private String evidenceDirectory(String root, long alertId, LocalDate date) {
        if (root == null || !root.matches("(?:/[A-Za-z0-9_-]+)+") || alertId <= 0) {
            throw new IllegalArgumentException("HDFS 视频证据目录无效");
        }
        return root + "/date=" + date + "/alert-" + alertId;
    }

    private void publishFile(String directory, String fileName, String token, byte[] payload)
            throws IOException, InterruptedException {
        String finalPath = directory + "/" + fileName;
        if (verifyIfPresent(finalPath, payload)) { return; }
        if (!booleanResult(requestToNameNode("PUT", directory, "MKDIRS&permission=750"), "MKDIRS")) {
            throw new IOException("WebHDFS 归档分区创建失败");
        }

        String tempPath = directory + "/." + fileName + "-" + token + ".tmp";
        try {
            var location = dataNode(requestToNameNode("PUT", tempPath, "CREATE&overwrite=false&permission=640"), tempPath);
            var upload = HttpRequest.newBuilder(location).timeout(IO_TIMEOUT)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            requireStatus(client.send(upload, HttpResponse.BodyHandlers.discarding()).statusCode(), 201, "CREATE");
            var rename = requestToNameNode("PUT", tempPath, "RENAME&destination=" + encode(finalPath));
            if (!booleanResult(rename, "RENAME") && !verifyIfPresent(finalPath, payload)) {
                throw new IOException("WebHDFS 归档文件发布失败");
            }
        } finally {
            removeTemporary(tempPath);
        }
    }

    private boolean verifyIfPresent(String path, byte[] payload) throws IOException, InterruptedException {
        var status = requestToNameNode("GET", path, "GETFILESTATUS");
        if (status.statusCode() == 404) { return false; }
        requireStatus(status.statusCode(), 200, "GETFILESTATUS");
        if (json.readTree(status.body()).path("FileStatus").path("length").asLong(-1) != payload.length) {
            throw new IOException("HDFS 同名归档文件长度不一致");
        }
        if (!Arrays.equals(readFile(path, payload.length), payload)) {
            throw new IOException("HDFS 同名归档文件内容不一致");
        }
        return true;
    }

    private byte[] readFile(String path, int expectedSize) throws IOException, InterruptedException {
        var location = dataNode(requestToNameNode("GET", path, "OPEN&length=" + (expectedSize + 1)), path);
        var read = HttpRequest.newBuilder(location).timeout(IO_TIMEOUT).GET().build();
        var response = client.send(read, HttpResponse.BodyHandlers.ofByteArray());
        requireStatus(response.statusCode(), 200, "OPEN");
        if (response.body().length != expectedSize) {
            throw new IOException("HDFS 文件内容长度不一致");
        }
        return response.body();
    }

    private HttpResponse<byte[]> requestToNameNode(String method, String path, String query)
            throws IOException, InterruptedException {
        URI uri = nameNode.resolve(REST_PREFIX + path + "?op=" + query + "&user.name=" + encode(user));
        var request = HttpRequest.newBuilder(uri).timeout(IO_TIMEOUT)
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI dataNode(HttpResponse<byte[]> response, String path) throws IOException {
        requireStatus(response.statusCode(), 307, "DataNode redirect");
        URI target;
        try {
            target = URI.create(response.headers().firstValue("Location").orElseThrow());
        } catch (RuntimeException exception) {
            throw new IOException("WebHDFS 缺少有效的 DataNode 地址", exception);
        }
        if (target.getHost() == null || !nameNode.getScheme().equals(target.getScheme())
                || target.getPort() != datanodePort || target.getRawUserInfo() != null
                || target.getRawFragment() != null || !Objects.equals(target.getPath(), REST_PREFIX + path)
                || target.getRawQuery() == null) {
            throw new IOException("WebHDFS 返回不受信任的 DataNode 地址");
        }
        String host = target.getHost().toLowerCase(Locale.ROOT);
        String address = datanodes.get(host);
        if (address == null && datanodes.containsValue(host)) { address = host; }
        if (address == null) { throw new IOException("WebHDFS DataNode 不在配置映射中"); }
        // 只替换已经验证的目标主机，保留服务端创建的操作参数。
        return URI.create(target.getScheme() + "://" + address + ":" + datanodePort
                + target.getRawPath() + "?" + target.getRawQuery());
    }

    private boolean booleanResult(HttpResponse<byte[]> response, String operation) throws IOException {
        requireStatus(response.statusCode(), 200, operation);
        return json.readTree(response.body()).path("boolean").asBoolean();
    }

    private void removeTemporary(String path) {
        try {
            requestToNameNode("DELETE", path, "DELETE&recursive=false");
        } catch (IOException exception) {
            log.warn("清理 HDFS 临时归档文件失败，异常类型={}", exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean validHost(String host) { return host != null && host.matches("[A-Za-z0-9.-]+"); }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static void requireStatus(int status, int expected, String operation) throws IOException {
        if (status != expected) { throw new IOException("WebHDFS " + operation + " 返回 HTTP " + status); }
    }
}
