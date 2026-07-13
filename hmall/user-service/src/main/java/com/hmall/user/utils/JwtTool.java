package com.hmall.user.utils;

import cn.hutool.core.exceptions.ValidateException;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTValidator;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.hmall.common.exception.UnauthorizedException;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.time.Duration;
import java.util.Date;

@Component
public class JwtTool {
    private final JWTSigner jwtSigner;

    public JwtTool(KeyPair keyPair) {
        this.jwtSigner = JWTSignerUtil.createSigner("rs256", keyPair);
    }

    /**
     * 创建 access-token，包含 jti（JWT ID）用于登出黑名单
     *
     * @param userId 用户ID
     * @param ttl   token 有效期
     * @return access-token
     */
    public String createToken(Long userId, Duration ttl) {
        // 1.生成jws，含 jti 用于登出时加入黑名单
        return JWT.create()
                .setJWTId(java.util.UUID.randomUUID().toString())
                .setPayload("user", userId)
                .setIssuedAt(new Date())
                .setExpiresAt(new Date(System.currentTimeMillis() + ttl.toMillis()))
                .setSigner(jwtSigner)
                .sign();
    }

    /**
     * 从 token 中提取 jti（JWT ID），用于登出时将 token 加入黑名单
     */
    public String getJti(String token) {
        JWT jwt = parseAndVerify(token);
        Object jti = jwt.getPayload("jti");
        return jti != null ? jti.toString() : null;
    }

    /**
     * 计算 token 剩余有效期（秒），用于黑名单 TTL 设置
     *
     * @return 剩余秒数，token 已过期返回 0
     */
    public long getRemainingTTL(String token) {
        JWT jwt = parseAndVerify(token);
        Date expiresAt = jwt.getPayloads().getDate(JWT.EXPIRES_AT);
        if (expiresAt == null) {
            return 0;
        }
        long remainingMs = expiresAt.getTime() - System.currentTimeMillis();
        return Math.max(remainingMs / 1000, 0);
    }

    /**
     * 解析并验证 token 签名，不校验过期时间（内部使用）
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
}