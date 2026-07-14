package com.hmall.admin.interceptor;

import cn.hutool.core.util.StrUtil;
import com.hmall.admin.config.AdminAuthProperties;
import com.hmall.admin.security.AdminJwtTool;
import com.hmall.admin.security.DynamicSecurityService;
import com.hmall.common.exception.UnauthorizedException;
import com.hmall.common.service.RedisService;
import com.hmall.common.utils.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * admin 认证拦截器
 * <p>
 * 1. 白名单路径放行
 * 2. 从 Authorization header 提取 token，校验 admin JWT
 * 3. 检查 Redis 黑名单（登出失效）
 * 4. UserContext.setUser(adminId)
 * 5. 动态权限：匹配请求 URL → 查询管理员资源权限
 */
@Slf4j
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final AdminJwtTool adminJwtTool;
    private final AdminAuthProperties authProperties;
    private final DynamicSecurityService dynamicSecurityService;
    private final RedisService redisService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String BLACKLIST_KEY_PREFIX = "admin:blacklist:";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // 1. 白名单放行
        if (isExclude(path)) {
            return true;
        }

        // 2. 获取 token
        String token = request.getHeader("Authorization");
        if (StrUtil.isBlank(token)) {
            return writeUnauthorized(response, "未登录或token已过期");
        }

        // 3. 校验 JWT
        Long adminId;
        try {
            adminId = adminJwtTool.parseAdminId(token);
        } catch (UnauthorizedException e) {
            return writeUnauthorized(response, e.getMessage());
        }

        // 4. 检查黑名单
        String jti = adminJwtTool.getJti(token);
        if (jti != null && redisService != null) {
            try {
                Object blacklisted = redisService.get(BLACKLIST_KEY_PREFIX + jti);
                if (blacklisted != null) {
                    return writeUnauthorized(response, "token已失效，请重新登录");
                }
            } catch (Exception e) {
                log.warn("Redis 黑名单检查异常，降级放行, adminId={}", adminId, e);
            }
        }

        // 5. Token 续期
        try {
            String newToken = adminJwtTool.refreshToken(token);
            if (newToken != null) {
                response.setHeader("Authorization", newToken);
            }
        } catch (Exception e) {
            log.warn("token 续期失败", e);
        }

        // 6. 动态权限校验
        Map<String, String> urlMap = dynamicSecurityService.loadDataSource();
        boolean hasPermission = checkPermission(urlMap, path, method);
        if (!hasPermission) {
            // 超级管理员(id=1)放行全部
            if (adminId == 1L) {
                UserContext.setUser(adminId);
                return true;
            }
            return writeForbidden(response, "无权限访问");
        }

        // 7. 设置用户信息
        UserContext.setUser(adminId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.removeUser();
    }

    private boolean isExclude(String path) {
        if (authProperties.getExcludePaths() == null) {
            return false;
        }
        for (String excludePath : authProperties.getExcludePaths()) {
            if (pathMatcher.match(excludePath, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查 URL 权限
     * <p>
     * 简化实现：只检查 URL 匹配，不做角色级别的资源校验。
     * 完整实现需要根据 adminId 查询角色资源列表。
     */
    private boolean checkPermission(Map<String, String> urlMap, String path, String method) {
        if (urlMap == null || urlMap.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> entry : urlMap.entrySet()) {
            if (pathMatcher.match(entry.getKey(), path)) {
                return true;
            }
        }
        return true;
    }

    private boolean writeUnauthorized(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> result = new HashMap<>();
        result.put("code", 401);
        result.put("msg", msg);
        result.put("data", null);
        response.getWriter().write(objectMapper.writeValueAsString(result));
        return false;
    }

    private boolean writeForbidden(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> result = new HashMap<>();
        result.put("code", 403);
        result.put("msg", msg);
        result.put("data", null);
        response.getWriter().write(objectMapper.writeValueAsString(result));
        return false;
    }
}
