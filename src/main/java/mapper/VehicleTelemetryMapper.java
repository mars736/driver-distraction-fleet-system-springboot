package mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vo.telemetry.VehicleLivePosition;
import vo.telemetry.VehiclePosition;
import vo.telemetry.StoppedMotionCheck;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface VehicleTelemetryMapper {
    record RunningTask(long id, LocalDateTime departedAt) { }
    record OfflineCandidate(long vehicleId, long driverId, long taskId) { }
    record MotionState(long vehicleId, long fleetId, long driverId, long stoppedAt, long lastSampledAt,
                       boolean aboveThreshold, Long checkId) { }

    List<VehiclePosition> findTrack(@Param("vehicleId") long vehicleId, @Param("start") long start,
                                    @Param("end") long end, @Param("limit") int limit);
    void insertSample(@Param("vehicleId") long vehicleId, @Param("driverId") long driverId,
                      @Param("sampledAt") long sampledAt, @Param("latitude") double latitude,
                      @Param("longitude") double longitude, @Param("speedKmh") Double speedKmh,
                      @Param("receivedAt") long receivedAt);
    VehiclePosition findLatestForDriver(@Param("vehicleId") long vehicleId, @Param("driverId") long driverId);
    List<VehicleLivePosition> findLivePositions(@Param("vehicleIds") List<Long> vehicleIds);
    List<OfflineCandidate> findOfflineCandidates(@Param("vehicleIds") List<Long> vehicleIds,
                                                  @Param("cutoff") long cutoff);
    RunningTask lockRunningTask(@Param("taskId") long taskId, @Param("vehicleId") long vehicleId,
                                @Param("driverId") long driverId, @Param("fleetId") long fleetId);
    int claimOfflineNotice(@Param("taskId") long taskId, @Param("vehicleId") long vehicleId,
                           @Param("driverId") long driverId, @Param("sampledAt") long sampledAt,
                           @Param("detectedAt") long detectedAt);
    Double findSpeedAt(@Param("vehicleId") long vehicleId, @Param("driverId") long driverId,
                       @Param("earliest") long earliest, @Param("capturedAt") long capturedAt);

    Long stoppedAt(long fleetId);
    MotionState motionState(long vehicleId);
    void saveMotionState(MotionState state);
    void clearMotionState(long vehicleId);
    void insertMotionCheck(StoppedMotionCheck check);
}
