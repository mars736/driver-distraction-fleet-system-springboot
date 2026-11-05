package mapper;

import entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户 Mapper，SQL 写在 resources/mapper/UserMapper.xml 里。
 */
@Mapper
public interface UserMapper {

    User selectById(Long id);

    User selectByUsername(String username);

    int countByUsername(String username);

    int countByEmployeeNo(String employeeNo);

    int insert(User user);

    int updatePassword(@Param("id") Long id,
                       @Param("password") String password,
                       @Param("tokenVersion") Integer tokenVersion);

    int updateEnabled(@Param("id") Long id,
                      @Param("enabled") boolean enabled,
                      @Param("tokenVersion") Integer tokenVersion);

    // ===== 司机档案 =====

    List<User> listDrivers(@Param("fleetId") Long fleetId,
                           @Param("keyword") String keyword,
                           @Param("offset") int offset,
                           @Param("pageSize") int pageSize);

    long countDrivers(@Param("fleetId") Long fleetId, @Param("keyword") String keyword);

    int countByEmployeeNoExclude(@Param("employeeNo") String employeeNo,
                                 @Param("excludeId") Long excludeId);

    int updateDriverProfile(User user);

    int updateVehicleId(@Param("id") Long id, @Param("vehicleId") Long vehicleId);

    int deleteById(Long id);
}
