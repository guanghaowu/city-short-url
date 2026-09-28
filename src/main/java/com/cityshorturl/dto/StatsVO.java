package com.cityshorturl.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 访问统计。
 *
 * <p><b>PV 与 UV 的区别（面试必会）</b>：
 * PV = 页面浏览量，一次访问算 1；UV = 独立访客数，同一 IP 同一天多次访问只算 1。
 * 所以 UV 恒 ≤ PV，且「按天 UV 求和」≠「总 UV」（同一访客跨天会重复计数）。
 * 这个差异要在返回体里说清楚，否则产品会拿着两个数字来问为什么对不上。
 */
@Data
@Builder
public class StatsVO {

    private String shortCode;

    /** 查询窗口天数 */
    private Integer days;

    /** 全量 PV / UV（跨天去重） */
    private Long totalPv;
    private Long totalUv;

    /** 按天明细（当天内去重） */
    private List<DailyStat> daily;

    @Data
    @Builder
    public static class DailyStat {
        /** yyyy-MM-dd */
        private String date;
        private Long pv;
        private Long uv;
    }
}
