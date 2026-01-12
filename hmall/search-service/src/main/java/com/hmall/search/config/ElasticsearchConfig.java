package com.hmall.search.config;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ClassName: ElasticsearchConfig
 * Package: com.hmall.item.config
 * Description:
 *
 * @Author Raiden
 * @Create 2026/1/9 17:08
 * @Version 1.0
 */
@Configuration
public class ElasticsearchConfig {

    @Bean
    public RestHighLevelClient restHighLevelClient() {
        // 2. 构建 RestClientBuilder，配置核心参数
        RestClientBuilder builder = RestClient.builder(
                        HttpHost.create("192.168.100.128:9200") // ES 地址
                );
        RestHighLevelClient client = new RestHighLevelClient(builder);
        return client;
    }

    // 4. 可选：配置 Bean 销毁时优雅关闭客户端（Spring 容器关闭时释放连接）
    @Bean(destroyMethod = "close")
    public RestHighLevelClient restHighLevelClientWithClose() {
        return restHighLevelClient();
    }
}
