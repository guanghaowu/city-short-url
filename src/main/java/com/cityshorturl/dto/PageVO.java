package com.cityshorturl.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

/**
 * 分页响应体。
 *
 * <p>为什么不直接把 MyBatis-Plus 的 {@code IPage} / {@code Page} 序列化给前端：
 * 它会带上一堆内部字段（{@code orders}、{@code optimizeCountSql}、
 * {@code searchCount}、{@code hitCount}…），既有信息泄露之嫌又让前端解析困难。
 * 对外只暴露 {@code current / size / total / records} 四个字段。
 */
@Data
@AllArgsConstructor
public class PageVO<T> {

    /** 当前页，从 1 开始 */
    private long current;
    private long size;
    private long total;
    private long pages;
    private List<T> records;

    public static <E, T> PageVO<T> of(IPage<E> page, Function<E, T> converter) {
        return new PageVO<>(
                page.getCurrent(),
                page.getSize(),
                page.getTotal(),
                page.getPages(),
                page.getRecords().stream().map(converter).toList());
    }
}
