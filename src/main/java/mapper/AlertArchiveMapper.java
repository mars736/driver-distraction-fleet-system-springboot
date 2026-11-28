package mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import service.alert.archive.ArchiveEvent;
import service.alert.archive.ArchiveEvent.Source;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AlertArchiveMapper {
    int advanceRevision(@Param("alertId") long alertId);

    int insertEvent(@Param("alertId") long alertId, @Param("source") Source source,
                    @Param("at") LocalDateTime at);

    void initializeBackfill();

    Backfill lockBackfill();

    List<Long> backfillIds(@Param("last") long last, @Param("through") long through, @Param("size") int size);

    int backfillEvents(@Param("last") long last, @Param("through") long through, @Param("at") LocalDateTime at);

    void advanceBackfill(@Param("last") long last);

    Batch lockDueBatch(@Param("at") LocalDateTime at);

    List<Long> lockUnassignedEvents(@Param("size") int size);

    void insertBatch(@Param("batch") Batch batch, @Param("at") LocalDateTime at);

    int attachEvents(@Param("id") String id, @Param("events") List<Long> events);

    void leaseBatch(@Param("id") String id, @Param("token") String token, @Param("until") LocalDateTime until);

    List<ArchiveEvent> events(@Param("id") String id);

    int complete(@Param("id") String id, @Param("token") String token, @Param("at") LocalDateTime at);

    int retry(@Param("id") String id, @Param("token") String token, @Param("at") LocalDateTime at,
              @Param("error") String error);

    record Batch(String id, LocalDate partitionDate) { }

    record Backfill(long throughId, long lastId) { }
}
