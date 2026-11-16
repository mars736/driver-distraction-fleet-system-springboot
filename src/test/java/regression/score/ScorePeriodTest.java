package regression.score;

import common.exception.BusinessException;
import common.constant.CommonConstant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import service.score.ScorePeriod;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class ScorePeriodTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-30T16:00:00Z"), ZoneOffset.UTC);

    @Test void defaultMonthUsesBeijingInsteadOfMachineTimezone() {
        var p = ScorePeriod.parse(null, clock);
        assertThat(p.month()).isEqualTo("2026-12");
        assertThat(p.start()).isEqualTo(LocalDateTime.parse("2026-12-01T00:00:00"));
        assertThat(p.end()).isEqualTo(LocalDateTime.parse("2027-01-01T00:00:00"));
    }
    @Test void leapMonthIncludesFebruary29() {
        var p = ScorePeriod.parse("2024-02", clock);
        assertThat(p.start()).isEqualTo(LocalDateTime.parse("2024-02-01T00:00:00"));
        assertThat(p.end()).isEqualTo(LocalDateTime.parse("2024-03-01T00:00:00"));
    }
    @ParameterizedTest
    @ValueSource(strings={"", "2026-00", "2026-13", "2027-01", "0999-12", "2026-11-01", "abcd"})
    void invalidOrFutureMonthIsRejected(String value) {
        assertThatThrownBy(() -> ScorePeriod.parse(value, clock)).isInstanceOf(BusinessException.class);
    }
    @ParameterizedTest
    @CsvSource({"100,EXCELLENT", "90,EXCELLENT", "89,GOOD", "75,GOOD", "74,FAIR", "60,FAIR", "59,POOR", "0,POOR"})
    void gradeBoundaries(int score, CommonConstant.ScoreLevel expected) {
        assertThat(CommonConstant.ScoreLevel.fromScore(score)).isEqualTo(expected);
    }
}
