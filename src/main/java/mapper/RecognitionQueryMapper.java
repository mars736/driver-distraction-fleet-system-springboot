package mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import service.recognition.RecognitionQueryFilter;
import vo.recognition.*;
import java.util.List;

@Mapper
public interface RecognitionQueryMapper {
    long count(@Param("filter") RecognitionQueryFilter filter);
    List<RecognitionView> list(@Param("filter") RecognitionQueryFilter filter, @Param("offset") long offset, @Param("limit") int limit);
    RecognitionView findAccessible(@Param("id") long id, @Param("owner") Long owner);
    List<RecognitionAlertView> linkedAlerts(@Param("ids") List<Long> ids, @Param("owner") Long owner);
    List<RecognitionAppealView> latestAppeals(@Param("ids") List<Long> ids, @Param("owner") Long owner);
}
