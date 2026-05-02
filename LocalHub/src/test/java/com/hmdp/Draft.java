package com.hmdp;

import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.*;


@SpringBootTest
public class Draft {
    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService executorService = Executors.newFixedThreadPool(500);

    @Test
    public void testRedisIdWorker(){
        executorService.submit(()->{
            Long id = redisIdWorker.nextId("shop");
            System.out.println(id);
        });
    }

}
