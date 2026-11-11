package mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import service.alert.AlertQueryFilter;
import vo.alert.AlertView;
import java.util.List;

@Mapper
public interface AlertQueryMapper {
    long count(@Param("filter") AlertQueryFilter filter);
    List<AlertView> list(@Param("filter") AlertQueryFilter filter,
                        @Param("offset") long offset, @Param("limit") int limit);
    AlertView findAccessible(@Param("id") long id, @Param("ownerDriverId") Long ownerDriverId);
}
