package mapper;

import entity.Camera;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 摄像头 Mapper，SQL 写在 resources/mapper/CameraMapper.xml 里。
 */
@Mapper
public interface CameraMapper {

    List<Camera> listEnabled();

    List<Camera> listAll();

    Camera selectById(Long id);

    int insert(Camera camera);

    int update(Camera camera);

    int updateEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);

    int deleteById(Long id);
}
