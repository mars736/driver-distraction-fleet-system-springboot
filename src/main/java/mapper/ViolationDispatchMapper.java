package mapper;

import dto.operations.ViolationDispatch.*;
import dto.operations.Workforce.Position;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ViolationDispatchMapper {
    void createFromAlert(@Param("alertId") long alertId);
    int applyRevocation(service.alert.live.AlertRevokedEvent event);
    int closeRevoked(@Param("id") long id, @Param("actor") long actor,
                     @Param("input") RevocationWrapup input, @Param("now") LocalDateTime now);
    Long alertId(@Param("id") long id);
    WorkOrder detail(@Param("id") long id);
    List<WorkOrder> list(@Param("filter") OrderFilter filter, @Param("beforeId") Long beforeId,
                         @Param("offset") long offset, @Param("size") int size);
    LegacyRevocationNote legacyRevocationNote(@Param("id") long id);
    int insertLegacyRevocationNote(LegacyRevocationNote note);
    List<Operator> operators();
    boolean eligibleOperator(@Param("id") long id);
    void reportPosition(@Param("id") long id, @Param("position") Position position, @Param("now") LocalDateTime now);
    int assign(@Param("id") long id, @Param("assigneeId") long assigneeId, @Param("now") LocalDateTime now);
    int start(@Param("id") long id, @Param("now") LocalDateTime now);
    int close(@Param("id") long id, @Param("actor") long actor, @Param("input") Close input, @Param("now") LocalDateTime now);
    long confirmedHighRisk(@Param("driverId") long driverId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
    int decidePersonnel(@Param("id") long id, @Param("actor") long actor,
                        @Param("input") PersonnelDecision input, @Param("now") LocalDateTime now);
}
