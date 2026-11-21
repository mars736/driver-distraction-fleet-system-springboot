# 识别记录查询审阅记录

基准：主分支 `7f132d6363d50b71bc75f34b6eaf9085ab86bd6f`。范围：本次识别查询新增文件与 README 入口。规格来源为 `docs/recognition-query.md`，约定来源为 `M0-对齐说明.md` 及用户要求。

## Standards

0 项问题。沿用 sys_user、fleet、PLATFORM_ADMIN / DRIVER、Result、PageResult 和 MyBatis 分层。新增代码含中文说明，未改动队友既有档案逻辑，未引入评分模块。独立安全链及异常处理器仅作用于识别查询接口。

## Spec

发现 1 项 P2，已修复：关键帧原先只在链接解析前限定 alerts，真实路径只限定整个上传根目录，可能经 alerts 内的 junction / 符号链接读取 appeals 材料。

修复后真实路径同样限定于真实上传根下的 alerts 类别。新增真实目录链接回归，确认链接本身可读取目标文件，但关键帧接口返回统一 404。独立 Spec 复核确认问题已解决；权限、分页、关联加载及事务快照未发现其他确定问题。

验证：Java 21 / MySQL 8.0.45 独立测试库，全量 173 项通过（识别查询 34 项），0 失败、0 错误、0 跳过。

Standards：0 项；Spec：1 项 P2 已修复，未解决 0 项。
