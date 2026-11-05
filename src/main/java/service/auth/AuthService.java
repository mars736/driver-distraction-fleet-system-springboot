package service.auth;

import common.exception.BusinessException;
import common.util.JwtUtil;
import dto.auth.ChangePasswordRequest;
import dto.auth.LoginRequest;
import dto.auth.RefreshTokenRequest;
import dto.auth.RegisterRequest;
import entity.Fleet;
import entity.User;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import mapper.FleetMapper;
import mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import vo.auth.FleetOptionVO;
import vo.auth.LoginResponse;
import vo.auth.UserProfileVO;

import java.util.List;

/**
 * 认证与权限模块的业务逻辑。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final FleetMapper fleetMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /** 注册页下拉：返回平台已启用的车队 */
    public List<FleetOptionVO> listEnabledFleets() {
        return fleetMapper.listEnabled();
    }

    /** 司机注册：工号全局唯一，注册成功默认可用 */
    public void register(RegisterRequest req) {
        Fleet fleet = fleetMapper.selectById(req.getFleetId());
        if (fleet == null || !Boolean.TRUE.equals(fleet.getEnabled())) {
            throw new BusinessException("所选车队不存在或已停用");
        }
        if (userMapper.countByEmployeeNo(req.getEmployeeNo()) > 0) {
            throw new BusinessException("该工号已存在");
        }
        if (userMapper.countByUsername(req.getPhone()) > 0) {
            throw new BusinessException("该手机号已注册");
        }

        User user = new User();
        user.setUsername(req.getPhone());   // 司机用手机号作为登录账号
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setName(req.getName());
        user.setPhone(req.getPhone());
        user.setFleetId(fleet.getId());
        user.setEmployeeNo(req.getEmployeeNo());
        user.setRole("DRIVER");
        user.setEnabled(true);
        user.setTokenVersion(0);
        userMapper.insert(user);
    }

    /** 登录：账号不存在 / 密码错误分别提示 */
    public LoginResponse login(LoginRequest req) {
        User user = userMapper.selectByUsername(req.getAccount());
        if (user == null) {
            throw new BusinessException(400, "账号不存在");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessException(403, "账号已被禁用");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException(400, "密码错误");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getTokenVersion());
        return new LoginResponse(token, user.getId(), user.getName(), user.getRole(), user.getFleetId());
    }

    /** token 续期：旧 token 仍有效且账号未被禁用时，签发新 token */
    public String refresh(RefreshTokenRequest req) {
        Claims claims;
        try {
            claims = jwtUtil.parseToken(req.getToken());
        } catch (Exception e) {
            throw new BusinessException(401, "token 无效或已过期");
        }
        Long userId = Long.valueOf(claims.getSubject());
        Integer tokenVersion = claims.get("tokenVersion", Integer.class);
        User user = userMapper.selectById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !user.getTokenVersion().equals(tokenVersion)) {
            throw new BusinessException(401, "token 已失效");
        }
        return jwtUtil.generateToken(user.getId(), user.getTokenVersion());
    }

    /** 修改密码：改完把 tokenVersion +1，旧 token 立即失效 */
    public void changePassword(Long userId, ChangePasswordRequest req) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPassword())) {
            throw new BusinessException(400, "原密码错误");
        }
        userMapper.updatePassword(
                userId,
                passwordEncoder.encode(req.getNewPassword()),
                user.getTokenVersion() + 1
        );
    }

    /** 个人中心：姓名、所属车队、工号、绑定车辆 */
    public UserProfileVO profile(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        UserProfileVO vo = new UserProfileVO();
        vo.setId(user.getId());
        vo.setName(user.getName());
        vo.setFleetId(user.getFleetId());
        vo.setEmployeeNo(user.getEmployeeNo());
        vo.setPhone(user.getPhone());
        vo.setRole(user.getRole());
        vo.setVehicleId(user.getVehicleId());

        if (user.getFleetId() != null) {
            Fleet fleet = fleetMapper.selectById(user.getFleetId());
            vo.setFleetName(fleet != null ? fleet.getName() : null);
        }
        return vo;
    }

    /** 管理员禁用 / 启用账号：状态切换时 tokenVersion +1，让旧 token 立即失效 */
    public void setEnabled(Long id, boolean enabled) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        userMapper.updateEnabled(id, enabled, user.getTokenVersion() + 1);
    }
}
