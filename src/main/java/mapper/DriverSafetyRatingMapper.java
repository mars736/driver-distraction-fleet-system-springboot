package mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import service.rating.DriverSafetyRating.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DriverSafetyRatingMapper {
    Integer lockControl();
    Batch active();
    Batch find(@Param("id") String id);
    boolean hasWeek(@Param("week") LocalDate week);
    int create(@Param("id") String id, @Param("at") LocalDateTime at, @Param("week") LocalDate week);
    void setActive(@Param("id") String id);
    boolean backfillComplete();
    boolean hasPendingArchive(@Param("cutoff") LocalDateTime cutoff);
    List<Roster> rosterAt(@Param("cutoff") LocalDateTime cutoff);
    List<ArchiveFile> archivedFiles(@Param("cutoff") LocalDateTime cutoff);
    void saveRoster(@Param("id") String id, @Param("rows") List<Roster> rows);
    void saveFiles(@Param("id") String id, @Param("rows") List<ArchiveFile> files);
    void freeze(@Param("id") String id, @Param("at") LocalDateTime at);
    List<Roster> loadRoster(@Param("id") String id);
    List<ArchiveFile> loadFiles(@Param("id") String id);
    void running(@Param("id") String id);
    void progress(@Param("id") String id, @Param("one") String one, @Param("two") String two);
    void saveResults(@Param("id") String id, @Param("rows") List<Result> results);
    void published(@Param("id") String id, @Param("at") LocalDateTime at);
    void setPublished(@Param("id") String id);
    void failed(@Param("id") String id, @Param("message") String message);
    List<DriverView> currentRatings(@Param("drivers") List<Long> ids);
}
