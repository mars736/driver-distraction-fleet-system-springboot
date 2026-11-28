package service.fleet;

import common.exception.BusinessException;

public class FleetInUseException extends BusinessException {
    public FleetInUseException(int vehicleCount, long driverCount, long orderCount) {
        super(409, "车队仍关联 %d 辆车辆和 %d 名司机%s，无法删除".formatted(vehicleCount, driverCount,
                orderCount == 0 ? "" : "及 %d 笔运输订单".formatted(orderCount)));
    }
}
