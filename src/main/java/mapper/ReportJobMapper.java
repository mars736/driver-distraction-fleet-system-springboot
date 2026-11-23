package mapper;

import entity.ReportJob;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReportJobMapper {
    void insert(ReportJob job);
    ReportJob byOwner(@Param("id") long id, @Param("owner") long owner);
    long count(@Param("owner") long owner);
    List<ReportJob> list(@Param("owner") long owner, @Param("offset") long offset, @Param("limit") int limit);

    ReportJob next();
    int claim(@Param("id") long id, @Param("token") String token, @Param("now") LocalDateTime now);
    int complete(@Param("id") long id, @Param("token") String token, @Param("now") LocalDateTime now,
                 @Param("expiry") LocalDateTime expiry, @Param("rows") int rows);
    void fail(@Param("id") long id, @Param("token") String token, @Param("now") LocalDateTime now,
              @Param("message") String message);
    List<ReportJob> stuck(@Param("cutoff") LocalDateTime cutoff);
    int recover(@Param("id") long id, @Param("token") String token, @Param("now") LocalDateTime now);
    List<ReportJob> expired(@Param("now") LocalDateTime now);
    void expire(@Param("id") long id, @Param("token") String token, @Param("now") LocalDateTime now);
    boolean activeToken(@Param("token") String token);
}
