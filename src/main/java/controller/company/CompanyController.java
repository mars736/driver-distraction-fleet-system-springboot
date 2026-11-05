package controller.company;

import common.response.PageResult;
import common.response.Result;
import entity.Company;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import service.company.CompanyService;

/**
 * 车队 REST 接口（M0）。
 * 路径与返回体都和 CameraController 保持一致：统一 Result 包装。
 */
@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @GetMapping
    public Result<PageResult<Company>> list(@RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") Integer page,
                                            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(companyService.list(keyword, page, pageSize));
    }

    @GetMapping("/{id}")
    public Result<Company> get(@PathVariable Long id) {
        return Result.success(companyService.getById(id));
    }

    @PostMapping
    public Result<Company> create(@Valid @RequestBody Company company) {
        return Result.success(companyService.create(company));
    }

    @PutMapping("/{id}")
    public Result<Company> update(@PathVariable Long id, @Valid @RequestBody Company company) {
        company.setId(id);
        return Result.success(companyService.update(company));
    }

    @PatchMapping("/{id}/enabled")
    public Result<Void> updateEnabled(@PathVariable Long id, @RequestParam Integer enabled) {
        companyService.updateEnabled(id, enabled);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        companyService.delete(id);
        return Result.success();
    }
}
