package com.mars736.driverdistractionfleetsystemspringboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.mars736", "common", "inferworker", "video", "controller", "service"})
@EnableScheduling
@MapperScan("mapper")
public class DriverDistractionFleetSystemSpringbootApplication {

    public static void main(String[] args) {
        SpringApplication.run(DriverDistractionFleetSystemSpringbootApplication.class, args);
    }

}//
