package service.score;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import java.time.*;
import java.time.format.DateTimeParseException;

/** 使用半开区间 [月初, 下月月初)，不会漏掉月底最后一毫秒。 */
public record ScorePeriod(String month, LocalDateTime start, LocalDateTime end,
                          int initial, int smsDeduction, int backlogDeduction) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static ScorePeriod parse(String value, Clock clock) {
        try {
            YearMonth current = YearMonth.now(clock.withZone(ZONE));
            YearMonth month = value == null ? current : YearMonth.parse(value);
            if (month.getYear() < 1000 || month.getYear() > 9998 || month.isAfter(current)) {
                throw new BusinessException(400, "月份应在 1000-01 至当前月之间");
            }
            return new ScorePeriod(month.toString(), month.atDay(1).atStartOfDay(),
                    month.plusMonths(1).atDay(1).atStartOfDay(), CommonConstant.SCORE_INIT,
                    CommonConstant.SCORE_DEDUCT_SMS_ALERT, CommonConstant.SCORE_DEDUCT_BACKLOG);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(400, "月份格式应为 yyyy-MM，例如 2026-11");
        }
    }
}
