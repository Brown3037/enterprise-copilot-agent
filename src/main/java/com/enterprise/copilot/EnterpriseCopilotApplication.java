package com.enterprise.copilot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.enterprise.copilot.mapper")
public class EnterpriseCopilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnterpriseCopilotApplication.class, args);
    }
}
