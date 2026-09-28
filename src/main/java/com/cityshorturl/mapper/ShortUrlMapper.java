package com.cityshorturl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cityshorturl.entity.ShortUrl;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ShortUrlMapper extends BaseMapper<ShortUrl> {

    /**
     * 查询所有有效短码，供布隆过滤器启动时全量预加载。
     *
     * <p>只查一列，避免把 TEXT 类型的 original_url 也拉出来。
     *
     * <p><b>注意</b>：数据量大时（百万级以上）不能这样一次性查，应该分批（keyset 分页）
     * 或改用 RedisBloom 由服务端维护。本项目演示规模下直接查。
     */
    @Select("SELECT short_code FROM short_url WHERE status = 1")
    List<String> selectAllShortCodes();
}
