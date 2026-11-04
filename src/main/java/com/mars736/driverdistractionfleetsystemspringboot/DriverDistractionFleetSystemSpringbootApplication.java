package com.mars736.driverdistractionfleetsystemspringboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

/**
 * 主类。M0 修正两处扫描配置（对应诊断结论 C1、C2）：
 *   1. scanBasePackages 补上 controller、service（业务 Bean 都在这两个顶层包里），
 *      并去掉不存在的 "camera" 包（项目里没有 camera 这个包，摄像头模块在 controller.camera / service.camera 下）；
 *   2. @MapperScan 由 "camera" 改为 "mapper"（Mapper 接口实际都在 mapper 包下）。
 */
@SpringBootApplication(scanBasePackages = {"com.mars736", "common", "controller", "service", "inferworker", "video"})
@MapperScan("mapper")
public class DriverDistractionFleetSystemSpringbootApplication {

    public static void main(String[] args) {
        SpringApplication.run(DriverDistractionFleetSystemSpringbootApplication.class, args);
    }

}//
