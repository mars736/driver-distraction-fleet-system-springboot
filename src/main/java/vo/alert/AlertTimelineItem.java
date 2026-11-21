package vo.alert;

import java.time.LocalDateTime;

public record AlertTimelineItem(int offset, LocalDateTime capturedAt, String url) { }
