package com.hmall.admin.security;

import cn.hutool.core.exceptions.ValidateException;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTValidator;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.hmall.admin.config.AdminJwtProperties;
import com.hmall.common.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;

/**
 * admin JWT 工具类（独立于 C 端 JWT，使用独立密钥库 admin.jks）
 * <p>
 * payload 包含:
 * - sub: admin_user.id
 * - username: 管理员用户名
 * - type: ADMIN（标识为管理员 token）
 * - jti: UUID（用于登出黑名单）
 * - iat / exp: 签发/过期时间
 */
@Slf4j
@Component
public class AdminJwtTool {
    private final JWTSigner jwtSigner;
    private final AdminJwtProperties jwtProperties;

    public AdminJwtTool(KeyPair adminKeyPair, AdminJwtProperties jwtProperties) {
        this.jwtSigner = JWTSignerUtil.createSigner("rs256", adminKeyPair);
        this.jwtProperties = jwtProperties;
    }

    /**
     * 创建 admin token
     */
    public String createToken(Long adminUserId, String username) {
        Date now = new Date();
        return JWT.create()
                .setJWTId(UUID.randomUUID().toString())
                .setPayload("sub", adminUserId.toString())
                .setPayload("username", username)
                .setPayload("type", "ADMIN")
                .setIssuedAt(now)
                .setExpiresAt(new Date(now.getTime() + jwtProperties.getTokenTTL().toMillis()))
                .setSigner(jwtSigner)
                .sign();
    }

    /**
     * 解析 token，返回 admin_user.id
     */
    public Long parseAdminId(String token) {
        JWT jwt = parseAndVerify(token);
        try {
            JWTValidator.of(jwt).validateDate();
        } catch (ValidateException e) {
            throw new UnauthorizedException("token已经过期");
        }
        String type = jwt.getPayloads().getStr("type");
        if (!"ADMIN".equals(type)) {
            throw new UnauthorizedException("非管理员token");
        }
        String sub = jwt.getPayloads().getStr("sub");
        if (sub == null) {
            throw new UnauthorizedException("无效的token");
        }
        try {
            return Long.valueOf(sub);
        } catch (RuntimeException e) {
            throw new UnauthorizedException("无效的token");
        }
    }

    /**
     * 从 token 中提取 jti（JWT ID），用于登出黑名单
     */
    public String getJti(String token) {
        JWT jwt = parseAndVerify(token);
        Object jti = jwt.getPayload("jti");
        return jti != null ? jti.toString() : null;
    }

    /**
     * 计算 token 剩余有效期（秒），用于黑名单 TTL 设置
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
     * 尝试续期 token
     */
    public String refreshToken(String oldToken) {
        JWT jwt = parseAndVerify(oldToken);
        Date issuedAt = jwt.getPayloads().getDate(JWT.ISSUED_AT);
        if (issuedAt == null) {
            return null;
        }
        LocalDateTime issuedTime = issuedAt.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        Duration elapsed = Duration.between(issuedTime, LocalDateTime.now());
        if (elapsed.compareTo(jwtProperties.getRefreshWindow()) < 0) {
            return null;
        }
        Long adminId = Long.valueOf(jwt.getPayloads().getStr("sub"));
        String username = jwt.getPayloads().getStr("username");
        log.debug("续期 admin token, adminId={}, 距签发={}min", adminId, elapsed.toMinutes());
        return createToken(adminId, username);
    }

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
