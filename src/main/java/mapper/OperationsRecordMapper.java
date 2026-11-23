package mapper;

import entity.User;
import entity.Vehicle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OperationsRecordMapper {
    @Select("SELECT * FROM sys_user WHERE id = #{id} FOR UPDATE")
    User lockUser(long id);

    @Select("SELECT * FROM vehicle WHERE id = #{id} FOR UPDATE")
    Vehicle lockVehicle(long id);

    @Select("SELECT LAST_INSERT_ID()")
    long generatedId();
}
