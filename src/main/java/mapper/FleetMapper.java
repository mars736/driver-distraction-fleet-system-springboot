package mapper;

import entity.Fleet;
import org.apache.ibatis.annotations.Mapper;
import vo.auth.FleetOptionVO;

import java.util.List;

/**
 * 车队 Mapper，SQL 写在 resources/mapper/FleetMapper.xml 里。
 */
@Mapper
public interface FleetMapper {

    List<FleetOptionVO> listEnabled();

    Fleet selectById(Long id);

    int countAll();

    int insert(Fleet fleet);
}
