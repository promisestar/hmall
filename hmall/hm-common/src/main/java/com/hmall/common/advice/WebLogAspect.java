package com.hmall.common.advice;

import cn.hutool.json.JSONUtil;
import com.hmall.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * 请求日志切面 — 零侵入记录所有 Controller 方法的请求/响应/耗时
 * 仅在 Servlet Web 环境下生效（Gateway 是响应式架构，跳过）
 *
 * @author hmall
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Slf4j
@Aspect
@Order(1)
@Component
public class WebLogAspect {

    @Around("execution(* com.hmall..controller..*.*(..))")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        // 1. 获取请求信息
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String uri = "";
        String method = "";
        String clientIp = "";
        Long userId = null;
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            uri = request.getRequestURI();
            method = request.getMethod();
            clientIp = getClientIp(request);
            userId = UserContext.getUser();
        }
        String classMethod = joinPoint.getSignature().getDeclaringTypeName()
                + "." + joinPoint.getSignature().getName();

        // 2. 记录请求入参（脱敏：截断过长参数）
        String args = truncateArgs(joinPoint.getArgs());

        log.info("API-START {} {} | class={} | client={} | userId={} | param={}",
                method, uri, classMethod, clientIp, userId, args);

        // 3. 执行目标方法
        Object result;
        try {
            result = joinPoint.proceed();
            long cost = System.currentTimeMillis() - start;
            log.info("API-END   {} {} | class={} | cost={}ms | result={}",
                    method, uri, classMethod, cost, truncateResult(result));
        } catch (Throwable e) {
            long cost = System.currentTimeMillis() - start;
            log.error("API-ERROR {} {} | class={} | cost={}ms | error={}",
                    method, uri, classMethod, cost, e.getMessage());
            throw e;
        }
        return result;
    }

    /**
     * 截断过长的参数，避免日志膨胀
     */
    private String truncateArgs(Object[] args) {
        if (args == null || args.length == 0) return "";
        try {
            String json = JSONUtil.toJsonStr(args);
            return json.length() > 500 ? json.substring(0, 500) + "..." : json;
        } catch (Exception e) {
            return "[serialize error]";
        }
    }

    /**
     * 截断过长的响应结果，避免日志膨胀
     */
    private String truncateResult(Object result) {
        if (result == null) return "null";
        try {
            String json = JSONUtil.toJsonStr(result);
            return json.length() > 300 ? json.substring(0, 300) + "..." : json;
        } catch (Exception e) {
            return "[serialize error]";
        }
    }

    /**
     * 获取客户端真实IP（处理多级代理）
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多级代理取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.substring(0, ip.indexOf(","));
        }
        return ip;
    }
}
