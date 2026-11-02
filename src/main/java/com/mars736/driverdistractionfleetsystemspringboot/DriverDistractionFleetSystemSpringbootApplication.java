package com.mars736.driverdistractionfleetsystemspringboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.mars736", "common", "inferworker"})
public class DriverDistractionFleetSystemSpringbootApplication {

    public static void main(String[] args) {
        SpringApplication.run(DriverDistractionFleetSystemSpringbootApplication.class, args);
    }

}//
