package mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RecognitionDeletionMapper {
    boolean isReferenced(@Param("id") long id);
    int delete(@Param("ids") List<Long> ids);
    void enqueue(@Param("path") String path);
    List<Long> due();
    String lock(@Param("id") long id);
    boolean fileIsReferenced(@Param("path") String path);
    void complete(@Param("id") long id);
    void reschedule(@Param("id") long id);
}
