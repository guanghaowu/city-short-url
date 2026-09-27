package com.cityshorturl;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 高并发短链生成系统启动类。
 *
 * <p>端口 8082：项目 A 已占用 8080（后端）/ 8081（前端）。
 */
@SpringBootApplication
@MapperScan("com.cityshorturl.mapper")
public class CityShortUrlApplication {

    public static void main(String[] args) {
        SpringApplication.run(CityShortUrlApplication.class, args);
    }
}
