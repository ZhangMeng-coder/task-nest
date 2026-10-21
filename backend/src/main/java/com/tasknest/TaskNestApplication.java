package com.tasknest;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.tasknest.persistence.mapper")
public class TaskNestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskNestApplication.class, args);
    }
}
