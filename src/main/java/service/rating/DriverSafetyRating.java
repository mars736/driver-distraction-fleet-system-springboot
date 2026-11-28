package service.rating;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class DriverSafetyRating {
    private DriverSafetyRating() { }

    public static final String RULE_VERSION = "lifetime-decay-v1";
    public enum Status { PENDING, RUNNING, PUBLISHED, FAILED }
    public enum Level {
        S, A, B, C, D;

        public static Level of(BigDecimal score) {
            if (score.compareTo(BigDecimal.valueOf(95)) >= 0) { return S; }
            if (score.compareTo(BigDecimal.valueOf(85)) >= 0) { return A; }
            if (score.compareTo(BigDecimal.valueOf(70)) >= 0) { return B; }
            if (score.compareTo(BigDecimal.valueOf(60)) >= 0) { return C; }
            return D;
        }
    }

    public record Batch(String id, Status status, LocalDate weekKey, LocalDateTime dataAsOf,
                        LocalDateTime frozenAt, LocalDateTime publishedAt, String stage1ApplicationId,
                        String stage2ApplicationId, String errorMessage) {
        public SafetyRatingBatchView view() {
            return new SafetyRatingBatchView(id, status, dataAsOf, publishedAt, stage1ApplicationId, stage2ApplicationId,
                    errorMessage, RULE_VERSION);
        }
    }

    public record SafetyRatingBatchView(String batchId, Status status, LocalDateTime dataAsOf, LocalDateTime publishedAt,
                            String stage1ApplicationId, String stage2ApplicationId, String errorMessage,
                            String ruleVersion) { }
    public record Roster(long driverId, LocalDateTime registeredAt, boolean hasDrivingHistory) { }
    public record ArchiveFile(String id, LocalDate partitionDate) { }
    public record Result(long driverId, long eventCount, BigDecimal score, Level level, boolean hasDrivingHistory) { }
    public record SafetyRatingView(String batchId, BigDecimal score, Level level, LocalDateTime dataAsOf,
                       LocalDateTime publishedAt, boolean hasDrivingHistory) { }

    public record DriverView(long driverId, String batchId, BigDecimal score, Level level, LocalDateTime dataAsOf,
                             LocalDateTime publishedAt, boolean hasDrivingHistory) {
        public SafetyRatingView view() { return new SafetyRatingView(batchId, score, level, dataAsOf, publishedAt, hasDrivingHistory); }
    }
}
