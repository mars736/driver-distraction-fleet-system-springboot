package service.alert.live;

import java.time.LocalDateTime;

public record AlertRevokedEvent(long alertId, Long fleetId, long reviewId, Source source, LocalDateTime revokedAt) {
    public enum Source { DIRECT_REVIEW, APPEAL }
}
