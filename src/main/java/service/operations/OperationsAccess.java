package service.operations;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.security.LoginUser;
import common.security.UserContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

final class OperationsAccess {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private OperationsAccess() { }

    static LoginUser admin() {
        var user = UserContext.get();
        check(CommonConstant.ROLE_PLATFORM_ADMIN.equals(user.role()), 403, "仅平台管理员可以操作");
        return user;
    }

    static LoginUser driver() {
        var user = UserContext.get();
        check(CommonConstant.ROLE_DRIVER.equals(user.role()), 403, "仅司机可以操作");
        return user;
    }

    static long driverScope(Long requested) {
        var user = UserContext.get();
        if (CommonConstant.ROLE_PLATFORM_ADMIN.equals(user.role())) {
            check(requested != null && requested > 0, 400, "请选择司机");
            return requested;
        }
        driver();
        check(requested == null || requested.equals(user.userId()), 404, "司机不存在");
        return user.userId();
    }

    static void check(boolean condition, int code, String message) {
        if (!condition) { throw new BusinessException(code, message); }
    }

    static LocalDateTime now() { return LocalDateTime.now(ZONE); }

    static void dateRange(LocalDate start, LocalDate end) {
        check(start != null && end != null && start.getYear() >= 1000 && end.getYear() <= 9998
                && start.isBefore(end) && !end.isAfter(start.plusDays(366)), 400, "日期范围应为 1 至 366 天");
    }

    static void window(LocalDateTime start, LocalDateTime end, int maxDays) {
        check(start != null && end != null && start.getYear() >= 1000 && end.getYear() <= 9998
                && start.isBefore(end) && !end.isAfter(start.plusDays(maxDays)), 400, "开始时间应早于结束时间，且范围有效");
    }

    static long offset(int page, int size) {
        check(page >= 1 && size >= 1 && size <= 100, 400, "页码从 1 开始，每页最多 100 条");
        return (page - 1L) * size;
    }
}
