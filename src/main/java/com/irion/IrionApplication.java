package com.irion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class IrionApplication {

    public static void main(String[] args) {
        SpringApplication.run(IrionApplication.class, args);
    }

}
