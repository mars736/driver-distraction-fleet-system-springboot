package com.mars736.driverdistractionfleetsystemspringboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication(scanBasePackages = {"com.mars736", "common", "inferworker", "video", "camera"})
@MapperScan("camera")
public class DriverDistractionFleetSystemSpringbootApplication {

    public static void main(String[] args) {
        SpringApplication.run(DriverDistractionFleetSystemSpringbootApplication.class, args);
    }

}//
