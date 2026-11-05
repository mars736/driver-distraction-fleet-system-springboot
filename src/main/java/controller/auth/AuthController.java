package controller.auth;

import common.response.Result;
import common.security.LoginUser;
import common.security.UserContext;
import dto.auth.ChangePasswordRequest;
import dto.auth.LoginRequest;
import dto.auth.RefreshTokenRequest;
import dto.auth.RegisterRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import service.auth.AuthService;
import vo.auth.FleetOptionVO;
import vo.auth.LoginResponse;
import vo.auth.UserProfileVO;

import java.util.List;

/**
 * 认证与权限模块接口。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 注册页：已启用车队下拉 */
    @GetMapping("/fleets")
    public Result<List<FleetOptionVO>> fleets() {
        return Result.success(authService.listEnabledFleets());
    }

    /** 司机注册 */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest req) {
        /*
        * @Valid 是 Java Bean Validation（Bean 校验）规范里的一个触发注解。它的作用是——告诉 Spring："在把请求体绑定到这个对象之后，立刻对它做一次字段校验"。

    先看 RegisterRequest.java，它的每个字段上都写了校验规则：

    // RegisterRequest.java
    @NotNull(message = "请选择所属车队")    // fleetId 不能为 null
    private Long fleetId;

    @NotBlank(message = "工号不能为空")     // employeeNo 不能为 null，也不能是空字符串
    private String employeeNo;

    @NotBlank(message = "密码不能为空")
    private String password;
    // ...
    这些 @NotNull、@NotBlank 是校验规则，但它们本身不会自动生效。必须有一个触发者来执行这些规则，@Valid 就是这个触发者。*/
        //如果校验失败，会抛出MethodArgumentNotValidException异常，被 GlobalExceptionHandler捕获，我在那里写了处理策略了的
        authService.register(req);
        return Result.success("注册成功", null);
    }

    /** 登录 */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.success("登录成功", authService.login(req));
    }

    /**
     * 退出。JWT 是无状态的，服务端不存会话；
     * 真正“退出”由前端删除本地 token，这里只是给个统一入口。
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.success("已退出", null);
    }

    /** token 续期 */
    @PostMapping("/refresh")
    public Result<String> refresh(@Valid @RequestBody RefreshTokenRequest req) {
        return Result.success("续期成功", authService.refresh(req));
    }

    /** 修改密码 */
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        LoginUser loginUser = UserContext.get();
        authService.changePassword(loginUser.userId(), req);
        return Result.success("修改成功", null);
    }

    /** 个人中心 */
    @GetMapping("/me")
    public Result<UserProfileVO> me() {
        LoginUser loginUser = UserContext.get();
        return Result.success(authService.profile(loginUser.userId()));
    }

    /** 管理员禁用 / 启用账号 */
    @PutMapping("/users/{id}/enabled")
    public Result<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        authService.setEnabled(id, enabled);
        return Result.success();
    }
}
