package mapper;

import entity.Vehicle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 车辆 Mapper，SQL 写在 resources/mapper/VehicleMapper.xml 里。
 */
@Mapper
public interface VehicleMapper {

    /** 分页列表，companyId 为空时查全部 */
    List<Vehicle> list(@Param("companyId") Long companyId,
                       @Param("offset") int offset,
                       @Param("pageSize") int pageSize);

    long count(@Param("companyId") Long companyId);

    Vehicle selectById(Long id);

    int insert(Vehicle vehicle);

    int update(Vehicle vehicle);

    int updateMonitorEnabled(@Param("id") Long id, @Param("monitorEnabled") Integer monitorEnabled);

    int deleteById(Long id);

    /** 车牌号查重（全局唯一）：excludeId 用于编辑时排除自己 */
    int countByPlateNo(@Param("plateNo") String plateNo, @Param("excludeId") Long excludeId);

    /** 某车队下的车辆数，车队详情统计用 */
    int countByCompanyId(@Param("companyId") Long companyId);
}
