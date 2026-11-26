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

    List<Long> listEnabledIds();
    List<Long> listIdsByVehicle(long vehicleId);
    Camera selectRunnable(long id);
    Camera lockById(long id);
    Long lockMonitoredCamera(@Param("id") long id, @Param("vehicleId") long vehicleId);
    boolean hasHistory(long id);

    List<Camera> listAll();

    Camera selectById(Long id);

    int insert(Camera camera);

    int update(Camera camera);

    int updateEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);

    int deleteById(Long id);
}
