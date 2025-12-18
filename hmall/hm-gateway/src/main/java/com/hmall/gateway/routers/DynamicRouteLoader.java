package com.hmall.gateway.routers;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * ClassName: DynamicRouteLoader
 * Package: com.hmall.gateway.routers
 * Description:
 *
 * @Author Raiden
 * @Create 2025/11/11 20:48
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicRouteLoader {

    private final NacosConfigManager nacosConfigManager;
    private final RouteDefinitionWriter writer;

    private final String dataId = "gateway-routes.json";
    private final String group = "DEFAULT_GROUP";

    // 保存上次更新的路由信息
    private Set<String> routeIds = new HashSet<>();

    @PostConstruct
    public void initRouteConfigLoader() throws NacosException {
        String configInfo = nacosConfigManager.getConfigService()
                .getConfigAndSignListener(dataId, group, 5000, new Listener() {
                    @Override
                    public Executor getExecutor() {
                        return null;
                    }

                    @Override
                    public void receiveConfigInfo(String configInfo) {
                        // 更新路由表
                        updateConfigInfo(configInfo);
                    }
                });
        // 更新路由表
        updateConfigInfo(configInfo);
    }
    public void updateConfigInfo(String configInfo){
        log.info("更新路由表: {}", configInfo);
        List<RouteDefinition> routeDefinitions = JSONUtil.toList(configInfo, RouteDefinition.class);
        // 清空现有路由信息
        for(String routeId: routeIds){
            writer.delete(Mono.just(routeId)).subscribe();
        }
        routeIds.clear();

        // 更新路由信息
        for(RouteDefinition routeDefinition: routeDefinitions){
            writer.save(Mono.just(routeDefinition)).subscribe();
            routeIds.add(routeDefinition.getId());
        }
    }
}
