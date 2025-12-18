package com.hmall.api.config;
import com.hmall.api.client.fallback.ItemClientFallbackFactory;
import com.hmall.common.utils.UserContext;
import feign.Logger;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/**
 * ClassName: DefaultFeignConfig
 * Package: com.hmall.api.config
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/7 20:12
 * @Version 1.0
 */
public class DefaultFeignConfig {
    @Bean
    public Logger.Level feignLogLevel(){
        return Logger.Level.FULL;
    }

    @Bean
    public RequestInterceptor userInfoRequestInterceptor(){
        return new RequestInterceptor() {
            @Override
            public void apply(feign.RequestTemplate requestTemplate) {
                Long userInfo = UserContext.getUser();
                if(userInfo != null){
                    requestTemplate.header("user-info", userInfo.toString());
                }
            }
        };
    }

    @Bean
    public ItemClientFallbackFactory itemClientFallbackFactory(){
        return new ItemClientFallbackFactory();
    }
}