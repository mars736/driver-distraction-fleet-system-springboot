package mapper;

import org.apache.ibatis.annotations.Param;
import service.alert.evidence.AlertVideoEvidence;

public interface AlertVideoEvidenceMapper {
    int enqueue(@Param("alertId") long alertId, @Param("cameraId") long cameraId, @Param("driverId") long driverId,
                @Param("streamId") String streamId, @Param("captured") long captured, @Param("due") long due);
    AlertVideoEvidence find(@Param("alertId") long alertId);
    AlertVideoEvidence lockDue(@Param("at") long at);
    int lease(@Param("alertId") long alertId, @Param("token") String token, @Param("until") long until);
    int prepared(@Param("alertId") long alertId, @Param("token") String token, @Param("start") long start,
                 @Param("end") long end, @Param("bytes") int bytes);
    int finish(@Param("alertId") long alertId, @Param("token") String token, @Param("status") AlertVideoEvidence.Status status,
               @Param("error") String error);
    int retry(@Param("alertId") long alertId, @Param("token") String token, @Param("due") long due,
              @Param("error") String error);
}
