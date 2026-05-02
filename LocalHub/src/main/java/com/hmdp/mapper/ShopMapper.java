package com.hmdp.mapper;

import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public interface ShopMapper extends BaseMapper<Shop> {
    // ShopMapper 继承 BaseMapper<Shop> 的原因：
    // 1. MyBatis-Plus 自动生成基础操作的 SQL 语句，提高开发效率
    // 2. 提供了批量操作方法，如批量插入、批量更新等

}
