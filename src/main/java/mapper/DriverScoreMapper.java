package mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import service.score.ScorePeriod;
import vo.score.*;
import java.util.List;

@Mapper
public interface DriverScoreMapper {
    boolean driverExists(@Param("driverId") long driverId);
    DriverScoreView summary(@Param("driverId") long driverId, @Param("period") ScorePeriod period);
    long countRecords(@Param("driverId") long driverId, @Param("period") ScorePeriod period);
    List<ScoreRecordView> records(@Param("driverId") long driverId, @Param("period") ScorePeriod period,
                                 @Param("offset") long offset, @Param("limit") int limit);
    long countDrivers(@Param("fleetId") Long fleetId);
    List<DriverScoreView> ranking(@Param("fleetId") Long fleetId, @Param("period") ScorePeriod period,
                                  @Param("offset") long offset, @Param("limit") int limit);
}
