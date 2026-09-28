package com.cityshorturl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cityshorturl.entity.AccessLog;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 访问日志查询。统计接口（{@code GET /api/short/stats/{code}}）从这里聚合。
 *
 * <h3>为什么这里直接聚合 access_log，而不用 access_stats 预聚合表</h3>
 * 预聚合表要由消费者在每次访问时 {@code INSERT ... ON DUPLICATE KEY UPDATE}
 * 累加，逻辑与一致性都要额外维护；而本项目当前规模下直接聚合完全够用。
 * <b>什么时候必须换预聚合</b>：access_log 到千万级时，
 * 实时 {@code COUNT(DISTINCT ip)} 会变成慢查询 —— 那时候再上
 * {@code access_stats}（按 short_code + stat_date 唯一键累加），
 * 也就是「用空间换时间 + 把写放大转移到异步侧」。
 */
@Mapper
public interface AccessLogMapper extends BaseMapper<AccessLog> {

    /** 按天聚合。UV 用 {@code COUNT(DISTINCT ip)} —— 同一 IP 当天多次访问只算 1 个 UV */
    @Select("""
            SELECT DATE(access_time) AS stat_date,
                   COUNT(*)          AS pv,
                   COUNT(DISTINCT ip) AS uv
            FROM access_log
            WHERE short_code = #{shortCode}
              AND access_time >= #{since}
            GROUP BY DATE(access_time)
            ORDER BY stat_date
            """)
    List<DailyStatRow> selectDailyStats(@Param("shortCode") String shortCode,
                                        @Param("since") LocalDateTime since);

    /** 全量 PV/UV。注意与「按天 UV 求和」不同：同一 IP 跨天访问，总 UV 算 1，按天算 2 */
    @Select("""
            SELECT COUNT(*) AS pv, COUNT(DISTINCT ip) AS uv
            FROM access_log
            WHERE short_code = #{shortCode}
            """)
    Map<String, Object> selectTotal(@Param("shortCode") String shortCode);

    /**
     * 一行按天聚合结果。
     *
     * <p>字段名写 {@code statDate} 而非 {@code stat_date}：依赖
     * {@code map-underscore-to-camel-case: true} 做下划线→驼峰转换。
     */
    @Data
    class DailyStatRow {
        private LocalDate statDate;
        private Long pv;
        private Long uv;
    }
}
