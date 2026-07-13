package com.hmall.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.common.exception.BadRequestException;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.exception.ForbiddenException;
import com.hmall.common.service.RedisService;
import com.hmall.common.utils.RedisLockUtil;
import com.hmall.common.utils.UserContext;

import com.hmall.user.config.JwtProperties;
import com.hmall.user.domain.dto.LoginByCodeDTO;
import com.hmall.user.domain.dto.LoginFormDTO;
import com.hmall.user.domain.po.User;
import com.hmall.user.domain.vo.UserLoginVO;
import com.hmall.user.enums.UserStatus;
import com.hmall.user.mapper.UserMapper;
import com.hmall.user.service.IUserService;
import com.hmall.user.utils.JwtTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.Random;
import java.util.UUID;

/**
 * <p>
 * 用户表 服务实现类
 * </p>
 *
 * @author 虎哥
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final PasswordEncoder passwordEncoder;

    private final JwtTool jwtTool;

    private final JwtProperties jwtProperties;

    private final RedisLockUtil redisLockUtil;

    private final RedisService redisService;

    private static final String DEDUCT_LOCK_PREFIX = "lock:deduct:";
    private static final long LOCK_EXPIRE_SECONDS = 5;

    @Override
    public UserLoginVO login(LoginFormDTO loginDTO) {
        // 1.数据校验
        String username = loginDTO.getUsername();
        String password = loginDTO.getPassword();
        // 2.根据用户名查询
        User user;
        try {
            user = lambdaQuery().eq(User::getUsername, username).one();
        } catch (Exception e) {
            throw new BizIllegalException("登录异常，请稍后重试", e);
        }
        Assert.notNull(user, "用户名错误");
        // 3.校验是否禁用
        if (user.getStatus() == UserStatus.FROZEN) {
            throw new ForbiddenException("用户被冻结");
        }
        // 4.校验密码
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BadRequestException("用户名或密码错误");
        }
        // 5.生成TOKEN
        String token = jwtTool.createToken(user.getId(), jwtProperties.getTokenTTL());
        // 6.封装VO返回
        UserLoginVO vo = new UserLoginVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setBalance(user.getBalance());
        vo.setToken(token);
        return vo;
    }

    // ==================== 3.5 验证码存储 ====================

    @Override
    public void sendCode(String phone) {
        // 1. 生成6位随机验证码
        String code = String.format("%06d", new Random().nextInt(999999));
        // 2. 模拟发送短信（生产环境对接短信SDK）
        log.info("【验证码】向手机 {} 发送验证码: {} （5分钟内有效）", phone, code);
        // 3. 存入 Redis，TTL 自动过期
        redisService.saveSmsCode(phone, code);
    }

    @Override
    public UserLoginVO loginByCode(LoginByCodeDTO dto) {
        String phone = dto.getPhone();
        String inputCode = dto.getCode();
        // 1. 从 Redis 获取验证码
        String cachedCode = redisService.getSmsCode(phone);
        if (cachedCode == null) {
            throw new BadRequestException("验证码已过期，请重新获取");
        }
        // 2. 对比验证码
        if (!cachedCode.equals(inputCode)) {
            throw new BadRequestException("验证码错误");
        }
        // 3. 验证通过后删除验证码（一次性使用）
        redisService.deleteSmsCode(phone);
        // 4. 根据手机号查询用户
        User user = lambdaQuery().eq(User::getPhone, phone).one();
        Assert.notNull(user, "手机号未注册");
        // 5. 校验是否禁用
        if (user.getStatus() == UserStatus.FROZEN) {
            throw new ForbiddenException("用户被冻结");
        }
        // 6. 生成 JWT token
        String token = jwtTool.createToken(user.getId(), jwtProperties.getTokenTTL());
        // 7. 封装返回
        UserLoginVO vo = new UserLoginVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setBalance(user.getBalance());
        vo.setToken(token);
        log.info("用户 {} 通过验证码登录成功", user.getUsername());
        return vo;
    }

    // ==================== 3.6 Token 黑名单（登出） ====================

    @Override
    public void logout(String token) {
        // 1. 提取 jti
        String jti = jwtTool.getJti(token);
        // 2. 计算 token 剩余有效期（秒）
        long remainingTTL = jwtTool.getRemainingTTL(token);
        if (remainingTTL <= 0) {
            log.info("token 已过期，无需加入黑名单, jti={}", jti);
            return;
        }
        // 3. 将 jti 加入 Redis 黑名单，TTL = token 剩余有效期
        redisService.addTokenToBlacklist(jti, remainingTTL);
        log.info("用户 {} 登出，token 已加入黑名单, jti={}, ttl={}s",
                UserContext.getUser(), jti, remainingTTL);
    }

    @Override
    public void deductMoney(String pw, Integer totalFee) {
        log.info("开始扣款");
        // 1.校验密码
        User user = getById(UserContext.getUser());
        if(user == null || !passwordEncoder.matches(pw, user.getPassword())){
            // 密码错误
            throw new BizIllegalException("用户密码错误");
        }

        // 2.获取分布式锁，防止同一用户并发扣款
        Long userId = UserContext.getUser();
        String lockKey = DEDUCT_LOCK_PREFIX + userId;
        String lockValue = UUID.randomUUID().toString();
        boolean locked = false;

        try {
            locked = redisLockUtil.tryLock(lockKey, lockValue, LOCK_EXPIRE_SECONDS);
        } catch (Exception e) {
            log.warn("Redis 分布式锁获取异常，降级无锁执行，userId={}", userId, e);
        }

        if (!locked) {
            throw new BizIllegalException("系统繁忙，请稍后重试");
        }

        try {
            // 3.尝试扣款
            baseMapper.updateMoney(userId, totalFee);
        } catch (Exception e) {
            throw new BizIllegalException("扣款失败，可能是余额不足！", e);
        } finally {
            // 4.释放锁
            try {
                redisLockUtil.releaseLock(lockKey, lockValue);
            } catch (Exception e) {
                log.warn("释放分布式锁失败，lockKey={}", lockKey, e);
            }
        }
        log.info("扣款成功");
    }
}
