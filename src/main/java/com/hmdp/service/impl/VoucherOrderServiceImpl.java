package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private IVoucherOrderService proxy;
    /**
     * 优惠券秒杀下单优化
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result sekillVocher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0，代表库存不足或者重复下单
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2.创建VoucherOrder对象
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        // 2.3.放入阻塞队列
        orderTasks.add(voucherOrder);
        // 3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderId);
    }

    // 异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            // 1.获取用户id
            Long userId = voucherOrder.getUserId();
            // 2.创建锁对象
            RLock redisLock = redissonClient.getLock("lock:order:" + userId);
            // 3.尝试获取锁
            boolean isLock = redisLock.tryLock();
            // 4.判断是否获取锁成功
            if(!isLock){
                // 获取锁失败，直接返回错误信息
                log.error("不允许重复下单！");
                return;
            }
            try {
                //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                // 释放锁
                redisLock.unlock();
            }
        }
    }

    /**
     * 创建订单
     * @param voucherOrder
     * @return
     */
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 5.1.查询订单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.3.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过了！");
            return;
        }
        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 库存不足
            log.error("库存不足！");
            return;
        }
        save(voucherOrder);
    }

    /**
     * 优惠券秒杀下单
     *
     * @param voucherId
     * @return
     */
    /*@Override
    public Result sekillVocher(Long voucherId) {
        // 1.查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }

        // 5.一人一单
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象(分布式锁)
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if(!isLock){
            // 获取锁失败，返回报错
            return Result.fail("不允许重复下单！");
        }
        try {
            // 保证事务的特性，同时控制锁的粒度
            // 获取代理对象,(事务想要生效，还得利用代理来生效，我们需要获得原始的事务对象，来操作事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }*/


    /**
     * 封装方法实现一人一单
     *
     * @param voucherId
     * @return
     */
    /*@Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        // 5.1.用户id
        Long userId = UserHolder.getUser().getId();
        // 5.2.查询订单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.3.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过了！");
        }
        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 库存不足
            return Result.fail("库存不足！");
        }
        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2.用户id
        voucherOrder.setUserId(userId);
        // 7.3.秒杀券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 8.返回订单id
        return Result.ok(orderId);
    }*/

}
