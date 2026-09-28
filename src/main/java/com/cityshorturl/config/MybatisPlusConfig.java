package com.cityshorturl.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p>⚠ 分页插件是必须<b>手动注册</b>的：不注册的话 {@code selectPage()} 不会报错，
 * 而是<b>静默地查全表再内存分页</b> —— 数据量小的时候看不出来，
 * 一上量就是慢查询。这是 MyBatis-Plus 最坑的静默失效之一。
 *
 * <p>另外 3.5.9 起 {@code PaginationInnerInterceptor} 被拆到了独立模块
 * {@code mybatis-plus-jsqlparser}，starter <b>不传递依赖</b>，
 * 必须显式引入（项目 A 踩过，这里直接带上）。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
