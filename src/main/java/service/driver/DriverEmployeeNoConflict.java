package service.driver;

import common.exception.BusinessException;

public final class DriverEmployeeNoConflict extends BusinessException {
    DriverEmployeeNoConflict() {
        super(409, "工号已被用作现有账号的登录名或工号");
    }
}
