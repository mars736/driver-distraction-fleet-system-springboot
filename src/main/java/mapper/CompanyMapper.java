package mapper;

import entity.Company;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 车队 Mapper，SQL 写在 resources/mapper/CompanyMapper.xml 里（沿用 CameraMapper 的写法）。
 */
@Mapper
public interface CompanyMapper {

    /** 分页列表，keyword 对编号/名称做模糊匹配 */
    List<Company> list(@Param("keyword") String keyword,
                       @Param("offset") int offset,
                       @Param("pageSize") int pageSize);

    long count(@Param("keyword") String keyword);

    Company selectById(Long id);

    int insert(Company company);

    int update(Company company);

    int updateEnabled(@Param("id") Long id, @Param("enabled") Integer enabled);

    int deleteById(Long id);

    /** 编号查重：excludeId 用于编辑时排除自己 */
    int countByCompanyNo(@Param("companyNo") String companyNo, @Param("excludeId") Long excludeId);
}
