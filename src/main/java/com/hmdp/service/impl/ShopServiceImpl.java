package com.hmdp.service.impl;

import ch.qos.logback.classic.spi.EventArgUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 缓存穿透(缓存空对象)
        // Shop shop = queryWithPassThrough(id);
        // 互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);
        // 逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if(shop == null){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从Redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中
        if(StrUtil.isBlank(shopJson)){
            // 3.未命中，直接返回null
            return null;
        }
        // 4.命中，先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断缓存是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            // 5.1.未过期，直接返回商铺信息
            return shop;
        }
        // 5.2.已过期，需要进行缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取成功
        if(isLock){
            // 再次检测缓存是否过期，如果没过期就不需要再重建（别的线程已经重建）
            shopJson = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            expireTime = redisData.getExpireTime();
            if(expireTime.isAfter(LocalDateTime.now())){
                // 返回前释放锁
                unlock(lockKey);
                // 未过期，直接返回店铺信息
                return shop;
            }
            // 6.3.获取互斥锁成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }

        // 6.4.获取互斥锁失败，直接返回已过期的商铺信息
        return shop;
    }

    /**
     * 解决商铺查询的缓存击穿问题(互斥锁 setnx命令)
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从Redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中
        if(StrUtil.isNotBlank(shopJson)){
            // 3.命中，直接返回商铺信息
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 4.判断命中的是否是空值（如果不是null，那么命中的就是空值）
        if(shopJson != null){
            // 是空值，返回错误信息
            return null;
        }
        // 5.未命中，实现缓存重建
        // 5.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 5.2.判断获取是否成功
            if(!isLock){
                // 5.3.失败，休眠一段时间重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 5.4.获取成功，重新查询缓存（看是否别的线程已经重建缓存）
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJson)){
                // 命中，直接返回商铺信息
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 5.5.根据id查询数据库
            shop = getById(id);
            // 模拟重建缓存的延时
            Thread.sleep(200);
            if(shop == null){
                // 5.6.数据库中不存在，将空值写入Redis，返回错误信息
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return shop;
            }
            // 5.7.数据库中存在，写入Redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 5.6.释放互斥锁
            unlock(lockKey);
        }
        // 6.返回
        return shop;
    }


    /**
     * 将带有逻辑过期时间的数据写入redis
     * @param id
     * @param expireSeconds
     */
    public void saveShop2Redis(Long id, long expireSeconds) throws InterruptedException {
        // 1.查询店铺数据
        Shop shop = getById(id);
        // 模拟延时
        Thread.sleep(200);
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 获取互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        // setnx命令
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 解决商铺查询的缓存穿透问题
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从Redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中
        if(StrUtil.isNotBlank(shopJson)){
            // 命中，直接返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 3.判断命中的是否是空值（如果不是null，那么命中的就是空值）
        if(shopJson != null){
            // 是空值，返回错误信息
            return null;
        }
        // 4.未命中，根据id查询数据库
        Shop shop = getById(id);
        // 判断是否存在
        if(shop == null){
            // 5.不存在，将空值写入Redis，返回错误信息
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return shop;
        }
        // 6.存在，写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7.返回
        return shop;
    }

    /**
     * 更新商铺信息
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        // 1.获取id并判断
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空！");
        }

        // 2.更新数据库
        updateById(shop);
        // 3.删除缓存中地商铺信息
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
