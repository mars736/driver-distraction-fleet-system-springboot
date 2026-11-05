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
