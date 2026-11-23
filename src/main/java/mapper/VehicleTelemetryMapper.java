package mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vo.telemetry.VehiclePosition;

import java.util.List;

@Mapper
public interface VehicleTelemetryMapper {
    List<VehiclePosition> findTrack(@Param("vehicleId") long vehicleId, @Param("start") long start,
                                    @Param("end") long end, @Param("limit") int limit);
    Long lockDriverBinding(@Param("driverId") long driverId, @Param("vehicleId") long vehicleId);

    void insertSample(@Param("vehicleId") long vehicleId, @Param("driverId") long driverId,
                      @Param("sampledAt") long sampledAt, @Param("latitude") double latitude,
                      @Param("longitude") double longitude, @Param("speedKmh") Double speedKmh);

    List<VehiclePosition> findLatestPositions(@Param("vehicleIds") List<Long> vehicleIds);

    List<VehiclePosition> findOfflineCandidates(@Param("vehicleIds") List<Long> vehicleIds,
                                                @Param("cutoff") long cutoff);

    int claimOfflineNotice(@Param("vehicleId") long vehicleId, @Param("driverId") long driverId,
                           @Param("sampledAt") long sampledAt, @Param("detectedAt") long detectedAt);

    Double findSpeedAt(@Param("vehicleId") long vehicleId, @Param("driverId") long driverId,
                       @Param("earliest") long earliest, @Param("capturedAt") long capturedAt);
}
