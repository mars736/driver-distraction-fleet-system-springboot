package mapper;

import entity.AlertRecord;
import entity.AlertReviewRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AlertReviewMapper {
    AlertRecord lockAlert(@Param("id") long id);
    int updateOpenStatus(@Param("id") long id, @Param("status") String status);
    int insertReview(AlertReviewRecord record);
    List<AlertReviewRecord> listReviews(@Param("alertId") long alertId);
}
