package com.hmall.common.interceptors;

import cn.hutool.core.exceptions.ValidateException;
import cn.hutool.core.util.StrUtil;
import com.hmall.common.utils.UserContext;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * ClassName: UserInfoInterceptor
 * Package: com.hmall.common.interceptors
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/9 17:18
 * @Version 1.0
 */
public class UserInfoInterceptor implements HandlerInterceptor {


    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserContext.removeUser();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取用户登录信息
        String userInfo = request.getHeader("user-info");
        // 2. 判断是否有用户信息，如果有，则存入TreadLocal中
        if(StrUtil.isNotBlank(userInfo)){
            UserContext.setUser(Long.valueOf(userInfo));
        }
        // 3. 放行
        return true;
    }
}
