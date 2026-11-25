package service.fleet;

import common.exception.BusinessException;

public class FleetInUseException extends BusinessException {
    public FleetInUseException(int vehicleCount, long driverCount) {
        super(409, "车队仍关联 %d 辆车辆和 %d 名司机，无法删除".formatted(vehicleCount, driverCount));
    }
}
