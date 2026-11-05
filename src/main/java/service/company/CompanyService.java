package service.company;

import common.exception.BusinessException;
import common.response.PageResult;
import entity.Company;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.CompanyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 车队业务（M0）。
 * 结构照 camera 的 Service 模板：构造注入 Mapper + @Transactional 包写操作 + 先校验后落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyMapper companyMapper;

    /** 分页查询，keyword 对编号/名称模糊匹配 */
    public PageResult<Company> list(String keyword, Integer page, Integer pageSize) {
        int currentPage = (page == null || page < 1) ? 1 : page;
        int size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 100);
        int offset = (currentPage - 1) * size;

        List<Company> list = companyMapper.list(keyword, offset, size);
        long total = companyMapper.count(keyword);
        return PageResult.of(list, total, currentPage, size);
    }

    public Company getById(Long id) {
        Company company = companyMapper.selectById(id);
        if (company == null) {
            throw new BusinessException(404, "车队不存在");
        }
        return company;
    }

    @Transactional
    public Company create(Company company) {
        if (companyMapper.countByCompanyNo(company.getCompanyNo(), null) > 0) {
            throw new BusinessException("车队编号已存在");
        }
        if (company.getEnabled() == null) {
            company.setEnabled(1);
        }
        companyMapper.insert(company);
        log.info("新增车队 id={}, companyNo={}", company.getId(), company.getCompanyNo());
        return company;
    }

    @Transactional
    public Company update(Company company) {
        getById(company.getId());
        if (companyMapper.countByCompanyNo(company.getCompanyNo(), company.getId()) > 0) {
            throw new BusinessException("车队编号已存在");
        }
        companyMapper.update(company);
        return company;
    }

    @Transactional
    public void updateEnabled(Long id, Integer enabled) {
        getById(id);
        companyMapper.updateEnabled(id, enabled);
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        companyMapper.deleteById(id);
        log.info("删除车队 id={}", id);
    }
}
