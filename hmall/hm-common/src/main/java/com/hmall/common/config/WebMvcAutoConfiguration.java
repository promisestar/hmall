package com.hmall.common.config;

import com.hmall.common.advice.CommonExceptionAdvice;
import com.hmall.common.advice.WebLogAspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Servlet MVC 环境专属自动配置（Gateway 等其他环境自动跳过）
 * <p>
 * 将 CommonExceptionAdvice 和 WebLogAspect 包装为 @Bean，
 * 确保在 EnableAutoConfiguration 机制下正确注册。
 *
 * @author hmall
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebMvcAutoConfiguration {

    @Bean
    public CommonExceptionAdvice commonExceptionAdvice() {
        return new CommonExceptionAdvice();
    }

    @Bean
    public WebLogAspect webLogAspect() {
        return new WebLogAspect();
    }
}
