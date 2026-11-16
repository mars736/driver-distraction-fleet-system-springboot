package mapper;

import dto.alert.AppealQueryRequest;
import entity.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import service.alert.AppealTargetType;
import java.util.List;

@Mapper
public interface AppealMapper {
    RecognitionRecord lockRecognition(@Param("id") long id);
    Long linkedBehaviorAlert(@Param("recognitionId") long recognitionId);
    AppealRecord find(@Param("id") long id, @Param("owner") Long owner);
    AppealRecord lock(@Param("id") long id);
    AppealRecord latestForTarget(@Param("type") AppealTargetType type, @Param("targetId") long targetId);
    Long pendingForAlert(@Param("alertId") long alertId);
    int insert(AppealRecord record);
    int finish(AppealRecord record);
    long count(@Param("q") AppealQueryRequest query, @Param("owner") Long owner);
    List<AppealRecord> list(@Param("q") AppealQueryRequest query, @Param("owner") Long owner, @Param("offset") long offset);
    int insertEvent(AppealEvent event);
    List<AppealEvent> events(@Param("appealId") long appealId);
    int insertMaterial(AppealMaterial material);
    List<Long> lockMaterialIds(@Param("appealId") long appealId);
    List<AppealMaterial> materials(@Param("appealId") long appealId);
    AppealMaterial material(@Param("appealId") long appealId, @Param("id") long id);
}
