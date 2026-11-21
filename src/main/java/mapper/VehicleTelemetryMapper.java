package mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VehicleTelemetryMapper {
    Long lockDriverBinding(@Param("driverId") long driverId, @Param("vehicleId") long vehicleId);

    void insertSample(@Param("vehicleId") long vehicleId, @Param("driverId") long driverId,
                      @Param("sampledAt") long sampledAt, @Param("latitude") double latitude,
                      @Param("longitude") double longitude, @Param("speedKmh") Double speedKmh);

    Double findSpeedAt(@Param("vehicleId") long vehicleId, @Param("driverId") long driverId,
                       @Param("earliest") long earliest, @Param("capturedAt") long capturedAt);
}
