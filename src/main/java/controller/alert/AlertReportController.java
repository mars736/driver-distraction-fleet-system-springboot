package controller.alert;

import dto.alert.AlertFilterRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.context.request.NativeWebRequest;
import service.alert.AlertQueryService;
import service.alert.AlertReportCsv;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class AlertReportController {
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final HeaderContentNegotiationStrategy ACCEPT = new HeaderContentNegotiationStrategy();
    private final AlertQueryService service;

    // 固定路径先与详情路径 /{id} 区分，再协商内容，避免把 export 当作告警 ID。
    @GetMapping("/api/alerts/export")
    public ResponseEntity<byte[]> export(@Valid @ModelAttribute AlertFilterRequest request, NativeWebRequest webRequest)
            throws HttpMediaTypeNotAcceptableException {
        boolean acceptsCsv = ACCEPT.resolveMediaTypes(webRequest).stream()
                .filter(mediaType -> mediaType.includes(CSV))
                .findFirst().map(mediaType -> mediaType.getQualityValue() > 0).orElse(false);
        if (!acceptsCsv) { throw new HttpMediaTypeNotAcceptableException(List.of(CSV)); }
        byte[] report = AlertReportCsv.encode(service.exportRows(request));
        return ResponseEntity.ok().contentType(CSV).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("alerts.csv").build().toString())
                .body(report);
    }
}
