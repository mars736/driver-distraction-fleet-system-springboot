package common.response;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页返回体（M0 新增，与 Result 并列，不改 Result）。
 * 用法：controller 里 return Result.success(PageResult.of(list, total, page, pageSize));
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页数据 */
    private List<T> list;

    /** 总条数 */
    private long total;

    /** 当前页码，从 1 开始 */
    private int page;

    /** 每页条数 */
    private int pageSize;

    public PageResult() {
    }

    public PageResult(List<T> list, long total, int page, int pageSize) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    public static <T> PageResult<T> of(List<T> list, long total, int page, int pageSize) {
        return new PageResult<>(list, total, page, pageSize);
    }
}
