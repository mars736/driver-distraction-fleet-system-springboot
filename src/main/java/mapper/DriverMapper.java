package mapper;

import entity.Driver;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 司机 Mapper，SQL 写在 resources/mapper/DriverMapper.xml 里。
 */
@Mapper
public interface DriverMapper {

    /** 分页列表，keyword 对工号/姓名做模糊匹配 */
    List<Driver> list(@Param("companyId") Long companyId,
                      @Param("keyword") String keyword,
                      @Param("offset") int offset,
                      @Param("pageSize") int pageSize);

    long count(@Param("companyId") Long companyId, @Param("keyword") String keyword);

    Driver selectById(Long id);

    int insert(Driver driver);

    int update(Driver driver);

    int updateAccountStatus(@Param("id") Long id, @Param("accountStatus") Integer accountStatus);

    /** 绑定/解绑车辆：vehicleId 传 null 即为解绑 */
    int updateVehicleId(@Param("id") Long id, @Param("vehicleId") Long vehicleId);

    int deleteById(Long id);

    /** company_id + 工号 查重：excludeId 用于编辑时排除自己 */
    int countByEmployeeNo(@Param("companyId") Long companyId,
                          @Param("employeeNo") String employeeNo,
                          @Param("excludeId") Long excludeId);

    /** 某车队下的司机数，车队详情统计用 */
    int countByCompanyId(@Param("companyId") Long companyId);
}
