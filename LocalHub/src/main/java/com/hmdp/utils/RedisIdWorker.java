package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    //开始时间戳
    private static final long BEGIN_TIMESTAMP=1640995200L; // 1640995200L是 2022年1月1日0时0分0秒 与 1970年1月1日0时0分0秒 的秒数差

    //序列号位数
    private static final int COUNT_BITS=32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    public Long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC); // 从1970年1月1日0时0分0秒开始的秒数
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //2.生成序列号
        //2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":"+ date);


        //3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }

}
