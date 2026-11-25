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
import service.audit.OperationAction;
import service.audit.OperationLogService;
import mapper.FleetMapper;
import mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    /*
    * 是 Spring Security 提供的一个密码加密器接口，专门解决一个问题：密码绝对不能明文存数据库。

    接口定义极其简单，只有两个方法：

    public interface PasswordEncoder {
    // 把明文密码加密成密文，存入数据库
    String encode(CharSequence rawPassword);

    // 明文 + 数据库里的密文，比对是否匹配
    boolean matches(CharSequence rawPassword, String encodedPassword);
}*/
    private final JwtUtil jwtUtil;
    private final OperationLogService audit;

    /** 注册页下拉：返回平台已启用的车队 */
    public List<FleetOptionVO> listEnabledFleets() {
        return fleetMapper.listEnabled();
    }

    /** 司机注册：工号全局唯一，注册成功默认可用 */
    @Transactional
    public void register(RegisterRequest req) {
        Fleet fleet = fleetMapper.lockById(req.getFleetId());
        if (fleet == null || !Boolean.TRUE.equals(fleet.getEnabled())) {
            throw new BusinessException("所选车队不存在或已停用");
        }
        if (userMapper.countByEmployeeNo(req.getEmployeeNo()) > 0) {
            throw new BusinessException("该工号已注册");
        }

        User user = new User();
        user.setUsername(req.getEmployeeNo());   // 司机用工号作为登录账号
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setName(req.getName());
        user.setPhone(req.getPhone());
        user.setFleetId(fleet.getId());
        user.setEmployeeNo(req.getEmployeeNo());
        user.setRole("DRIVER");
        user.setEnabled(true);
        user.setTokenVersion(0);
        userMapper.insert(user);
        audit.authSuccess(OperationAction.AUTH_REGISTER, user);
    }

    /** 登录：账号不存在 / 密码错误分别提示 */
    public LoginResponse login(LoginRequest req) {
        User user = userMapper.selectByUsername(req.getAccount());
        if (user == null) {
            audit.loginFailure(null, OperationLogService.LoginFailure.ACCOUNT_NOT_FOUND);
            throw new BusinessException(400, "账号不存在");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            audit.loginFailure(user, OperationLogService.LoginFailure.ACCOUNT_DISABLED);
            throw new BusinessException(403, "账号已被禁用");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {// matches返回 boolean 值，判断密码是否匹配
            audit.loginFailure(user, OperationLogService.LoginFailure.BAD_PASSWORD);
            throw new BusinessException(400, "密码错误");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getTokenVersion());
        audit.authSuccess(OperationAction.AUTH_LOGIN, user);
        return new LoginResponse(token, user.getId(), user.getName(), user.getRole(), user.getFleetId());
    }

    /** token 续期：单设备登录——续期时 tokenVersion +1，旧 token 立即失效，只保留最新 token */
    @Transactional
    public String refresh(RefreshTokenRequest req) {
        Claims claims;
        try {
            claims = jwtUtil.parseToken(req.getToken());
        } catch (Exception e) {
            throw new BusinessException(401, "token 格式非法或遭篡改或已过期");
        }
        Long userId = Long.valueOf(claims.getSubject());
        Integer tokenVersion = claims.get("tokenVersion", Integer.class);
        User user = userMapper.selectById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !user.getTokenVersion().equals(tokenVersion)) {
            throw new BusinessException(401, "token 已失效");
        }
        int newVersion = user.getTokenVersion() + 1;
        userMapper.bumpTokenVersion(userId, newVersion);
        return jwtUtil.generateToken(userId, newVersion);
    }

    /** 修改密码：改完把 tokenVersion +1，旧 token 立即失效 */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {//userId是controller从上下文获取的
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPassword())) {
            throw new BusinessException(400, "原密码错误");
        }
        userMapper.updatePassword(
                userId,
                passwordEncoder.encode(req.getNewPassword()),// encode返回 BCrypt 算法处理后的密文
                user.getTokenVersion() + 1
        );
        audit.success(OperationAction.AUTH_PASSWORD, userId, user.getFleetId());
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

        if (user.getFleetId() != null) {//这里我写这么复杂是为了之后fleet表新增车牌等字段做准备
            Fleet fleet = fleetMapper.selectById(user.getFleetId());
            vo.setFleetName(fleet != null ? fleet.getName() : null);
        }
        return vo;
    }

    /** 管理员禁用 / 启用账号：状态切换时 tokenVersion +1，让旧 token 立即失效 */
    @Transactional
    public void setEnabled(Long id, boolean enabled, Long operatorId) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (!enabled) {
            if (id.equals(operatorId)) {
                throw new BusinessException("不能停用当前登录账号");
            }
            if ("PLATFORM_ADMIN".equals(user.getRole()) && userMapper.countEnabledAdmins() <= 1) {
                throw new BusinessException("不能停用最后一个可用管理员");
            }
        }
        userMapper.updateEnabled(id, enabled, user.getTokenVersion() + 1);
        audit.success(OperationAction.AUTH_ENABLED, id, user.getFleetId(), enabled ? "ENABLED" : "DISABLED");
    }

    /** 退出登录：tokenVersion +1，让该用户所有已签发的 token 立即失效（全局退出） */
    @Transactional
    public void logout(Long userId) {
        User user = userMapper.selectById(userId);//userId:刚刚controller从上下文获取的userId
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        userMapper.bumpTokenVersion(userId, user.getTokenVersion() + 1);
        audit.authSuccess(OperationAction.AUTH_LOGOUT, user);
    }
}
