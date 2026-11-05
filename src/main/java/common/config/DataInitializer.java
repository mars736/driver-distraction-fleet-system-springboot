package common.config;

import entity.Fleet;
import entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.FleetMapper;
import mapper.UserMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动时初始化：一个默认车队、一个默认平台管理员。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final FleetMapper fleetMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (fleetMapper.countAll() == 0) {
            Fleet fleet = new Fleet();
            fleet.setName("默认车队");
            fleet.setEnabled(true);
            fleetMapper.insert(fleet);
        }

        if (userMapper.countByUsername("admin") == 0) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setName("平台管理员");
            admin.setRole("PLATFORM_ADMIN");
            admin.setEnabled(true);
            admin.setTokenVersion(0);
            userMapper.insert(admin);
            log.info("已创建默认管理员：admin / admin123，请登录后尽快修改密码");
        }
    }
}
