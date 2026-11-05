package mapper;

import entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
