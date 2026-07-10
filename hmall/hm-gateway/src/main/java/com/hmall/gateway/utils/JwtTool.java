package com.hmall.gateway.utils;

import cn.hutool.core.exceptions.ValidateException;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTValidator;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.hmall.common.exception.UnauthorizedException;
import com.hmall.gateway.config.JwtProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * JWT 工具类：创建、解析、续期
 */
@Slf4j
@Component
public class JwtTool {
    private final JWTSigner jwtSigner;
    private final JwtProperties jwtProperties;

    public JwtTool(KeyPair keyPair, JwtProperties jwtProperties) {
        this.jwtSigner = JWTSignerUtil.createSigner("rs256", keyPair);
        this.jwtProperties = jwtProperties;
    }

    /**
     * 创建 access-token，payload 中包含 userId 和签发时间
     */
    public String createToken(Long userId, Duration ttl) {
        Date now = new Date();
        return JWT.create()
                .setPayload("user", userId)
                .setIssuedAt(now)
                .setExpiresAt(new Date(now.getTime() + ttl.toMillis()))
                .setSigner(jwtSigner)
                .sign();
    }

    /**
     * 解析 token，返回 userId
     */
    public Long parseToken(String token) {
        JWT jwt = parseAndVerify(token);
        // 校验是否过期
        try {
            JWTValidator.of(jwt).validateDate();
        } catch (ValidateException e) {
            throw new UnauthorizedException("token已经过期");
        }
        // 提取 userId
        Object userPayload = jwt.getPayload("user");
        if (userPayload == null) {
            throw new UnauthorizedException("无效的token");
        }
        try {
            return Long.valueOf(userPayload.toString());
        } catch (RuntimeException e) {
            throw new UnauthorizedException("无效的token");
        }
    }

    /**
     * 尝试续期 token
     * <p>
     * 如果距上次签发超过 refreshWindow，则生成新 token 返回；
     * 如果在 refreshWindow 内，返回 null（无需续期，防高频刷新）。
     *
     * @param oldToken 原始 token
     * @return 新 token，若无需续期则返回 null
     */
    public String refreshToken(String oldToken) {
        JWT jwt = parseAndVerify(oldToken);
        // 获取签发时间
        Date issuedAt = jwt.getPayloads().getDate(JWT.ISSUED_AT);
        if (issuedAt == null) {
            // 旧版 token 无签发时间，降级为返回原 token（不续期）
            return null;
        }
        // 计算距签发已过多久
        LocalDateTime issuedTime = issuedAt.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        Duration elapsed = Duration.between(issuedTime, LocalDateTime.now());
        if (elapsed.compareTo(jwtProperties.getRefreshWindow()) < 0) {
            // 在冷却窗口内，无需续期
            return null;
        }
        // 超过冷却窗口，生成新 token（保留过期时间不变，仅刷新签发时间）
        Long userId = Long.valueOf(jwt.getPayload("user").toString());
        Duration remainingTTL = jwtProperties.getTokenTTL();
        log.debug("续期 token，userId={}, 距签发={}min", userId, elapsed.toMinutes());
        return createToken(userId, remainingTTL);
    }

    /**
     * 解析并验证 token 签名，不校验过期时间
     */
    private JWT parseAndVerify(String token) {
        if (token == null) {
            throw new UnauthorizedException("未登录");
        }
        JWT jwt;
        try {
            jwt = JWT.of(token).setSigner(jwtSigner);
        } catch (Exception e) {
            throw new UnauthorizedException("无效的token", e);
        }
        if (!jwt.verify()) {
            throw new UnauthorizedException("无效的token");
        }
        return jwt;
    }
}