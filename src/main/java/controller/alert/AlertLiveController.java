package controller.alert;

import common.response.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import service.alert.live.AlertLiveStream;

@RestController
@RequiredArgsConstructor
public class AlertLiveController {
    public static final String PATH = "/api/alerts/stream";
    public static final String TICKET_PATH = "/api/alerts/stream-ticket";
    static final String SUBSCRIPTION = AlertLiveController.class.getName() + ".subscription";
    private final AlertLiveStream stream;

    @PostMapping(TICKET_PATH)
    public ResponseEntity<Result<AlertLiveStream.StreamTicket>> issueTicket(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(stream.issueTicket(authorization)));
    }

    @GetMapping(PATH)
    public ResponseEntity<SseEmitter> subscribe(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "ticket", required = false) String ticket, HttpServletRequest request) {
        var subscription = authorization == null && ticket != null
                ? stream.openWithTicket(ticket) : stream.open(authorization);
        request.setAttribute(SUBSCRIPTION, subscription);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(subscription.emitter());
    }

    @RequestMapping(value = PATH, method = RequestMethod.HEAD)
    public ResponseEntity<Void> head() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "GET").build();
    }
}
