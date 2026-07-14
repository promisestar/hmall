package com.hmall.admin.security;

import java.util.Map;

/**
 * 动态权限数据源接口
 * <p>
 * 启动时从数据库加载全部资源 URL→权限编码映射，
 * AdminAuthInterceptor 每次请求时调用此数据源匹配请求 URL。
 */
@FunctionalInterface
public interface DynamicSecurityService {
    Map<String, String> loadDataSource();
}
