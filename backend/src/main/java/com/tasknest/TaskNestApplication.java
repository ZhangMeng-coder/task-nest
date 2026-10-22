package com.tasknest;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * TaskNest 后端启动类。扫描 {@code com.tasknest} 下的组件，绑定
 * {@code tasknest.*} 配置，并注册 MyBatis-Plus Mapper。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.tasknest.persistence.mapper")
public class TaskNestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskNestApplication.class, args);
    }
}
