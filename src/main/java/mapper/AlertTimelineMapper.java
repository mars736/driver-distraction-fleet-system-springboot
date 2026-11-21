package mapper;

import entity.AlertTimelineFrame;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AlertTimelineMapper {
    void insert(@Param("alertId") long alertId, @Param("frame") AlertTimelineFrame frame);
    List<AlertTimelineFrame> list(long alertId);
}
