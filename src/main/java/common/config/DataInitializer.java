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
        /*
        * String... 意思是——这个方法可以接收零个、一个或多个 String 参数。编译器在底层把它当成 String[]（字符串数组）来处理。

    语法对比
    // 传统数组写法（调用方必须先构造数组）
    public void run(String[] args) { ... }
    runner.run(new String[] {"--debug", "--port=8080"});

    // 可变参数写法（调用方直接传多个字符串，更简洁）
    public void run(String... args) { ... }
    runner.run("--debug", "--port=8080");  // 多个参数用逗号隔开
    runner.run();                          // 也可以不传参数*/
        if (fleetMapper.countAll() == 0) {
            Fleet fleet = new Fleet();
            fleet.setFleetNo("FLEET-001");
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
