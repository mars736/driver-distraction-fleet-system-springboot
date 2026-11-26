package vo.alert;

import java.time.LocalDateTime;

public record AlertFrameAvailability(int offset, Status status, LocalDateTime capturedAt, String url) {
    public enum Status { AVAILABLE, PENDING, NO_FRAME, UNREADABLE }
}
