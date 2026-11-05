package mapper;

import entity.Fleet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vo.auth.FleetOptionVO;

import java.util.List;

/**
 * 车队 Mapper，SQL 写在 resources/mapper/FleetMapper.xml 里。
 */
@Mapper
public interface FleetMapper {

    // ===== 认证注册下拉用 =====

    List<FleetOptionVO> listEnabled();

    int countAll();

    // ===== 车队档案 CRUD =====

    List<Fleet> list(@Param("keyword") String keyword,
                     @Param("offset") int offset,
                     @Param("pageSize") int pageSize);

    long count(@Param("keyword") String keyword);

    Fleet selectById(Long id);

    int insert(Fleet fleet);

    int update(Fleet fleet);

    int updateEnabled(@Param("id") Long id, @Param("enabled") Boolean enabled);

    int deleteById(Long id);

    int countByFleetNo(@Param("fleetNo") String fleetNo, @Param("excludeId") Long excludeId);
}
