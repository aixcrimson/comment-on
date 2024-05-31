package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询店铺类型信息
     * @return
     */
    @Override
    public Result queryTypeList() {
        String key = CACHE_SHOP_TYPE_KEY;
        // 1.从Redis中查询缓存
        String shopType = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否命中
        if(StrUtil.isNotBlank(shopType)){
            // 3.命中，直接返回店铺类型信息
            List<ShopType> typeList = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(typeList);
        }
        // 4.未命中，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 5.存在，缓存到Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList));
        // 6.返回
        return Result.ok(typeList);
    }
}
