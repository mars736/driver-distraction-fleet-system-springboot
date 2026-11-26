package service.camera;

import common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import video.VideoCaptureProperties;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class CameraSourceCatalog {
    private static final String PREFIX = "source:";
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private final VideoCaptureProperties video;

    public record SourceOption(String sourceId, String sourceKind) { }

    public List<SourceOption> options() {
        return video.getApprovedSources().keySet().stream().sorted()
                .filter(id -> approved(id) != null)
                .map(id -> new SourceOption(id, kind(approved(id))))
                .toList();
    }

    public String reference(String sourceId, String legacyUrl) {
        if (sourceId != null && legacyUrl != null) {
            throw new BusinessException(400, "请只指定一种视频源");
        }
        String id = sourceId != null ? sourceId : legacyId(legacyUrl);
        if (id == null || approved(id) == null) {
            throw new BusinessException(400, "请使用已批准的视频源");
        }
        return PREFIX + id;
    }

    public String resolve(String stored) {
        return approved(sourceId(stored));
    }

    public String sourceId(String stored) {
        if (stored == null || stored.isBlank()) { return null; }
        if (!stored.startsWith(PREFIX)) { return legacyId(stored); }
        String id = stored.substring(PREFIX.length());
        return ID.matcher(id).matches() ? id : null;
    }

    public String sourceKind(String stored) {
        String configured = resolve(stored);
        return configured == null ? null : kind(configured);
    }

    private String legacyId(String url) {
        if (url == null || url.isBlank()) { return null; }
        return video.getApprovedSources().entrySet().stream()
                .filter(entry -> url.equals(entry.getValue()) && approved(entry.getKey()) != null)
                .map(Map.Entry::getKey).min(String::compareTo).orElse(null);
    }

    private String approved(String id) {
        if (id == null || !ID.matcher(id).matches()) { return null; }
        String endpoint = video.getApprovedSources().get(id);
        if (endpoint == null) { return null; }
        try {
            URI uri = URI.create(endpoint);
            String scheme = uri.getScheme();
            if (uri.getHost() != null && uri.getFragment() == null && scheme != null
                    && switch (scheme.toLowerCase(Locale.ROOT)) {
                        case "rtsp", "http", "https" -> true;
                        default -> false;
                    }) {
                return endpoint;
            }
        } catch (IllegalArgumentException ignored) {
            // 底层 URL 错误可能带有凭据，接口与日志均不返回。
        }
        return null;
    }

    private static String kind(String endpoint) {
        return URI.create(endpoint).getScheme().toUpperCase(Locale.ROOT);
    }
}
