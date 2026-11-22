package mapper;

import entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OperationLogMapper {
    void insert(OperationLog row);
    List<OperationLog> list(@Param("fleetId") Long fleetId, @Param("actorId") Long actorId,
            @Param("action") String action, @Param("targetId") Long targetId,
            @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime,
            @Param("offset") long offset, @Param("limit") int limit);
    long count(@Param("fleetId") Long fleetId, @Param("actorId") Long actorId,
            @Param("action") String action, @Param("targetId") Long targetId,
            @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
}
