package com.hmdp.config;

import com.hmdp.utils.RedisConstants;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 添加 setPassword 方法并传入密码
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setPassword("zxcvbn123");

        return Redisson.create(config);
    }
}

