package com.hmall.item.es;

import cn.hutool.json.JSONUtil;
import com.hmall.item.domain.dto.ItemDoc;
import org.apache.http.HttpHost;
import org.apache.lucene.search.TotalHits;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * ClassName: ElasticSeachTest
 * Package: com.hmall.item.es
 * Description:
 *
 * @Author Raiden
 * @Create 2025/12/3 19:49
 * @Version 1.0
 */
@SpringBootTest(properties = "spring.profiles.active=local")
public class ElasticSeachTest {

    private RestHighLevelClient client;

    @Test
    void testMatchall() throws IOException {
        SearchRequest request = new SearchRequest("items");
        request.source().query(
                QueryBuilders.matchAllQuery());
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);

        parseSource(response);
    }

    private static void parseSource(SearchResponse response) {
        // 解析响应结果
        SearchHits searchHits = response.getHits();
        // 解析总条数
        long total = searchHits.getTotalHits().value;
        System.out.println("查询总条数： " + total);

        // 解析结果数据
        SearchHit[] hits = searchHits.getHits();
        for(SearchHit hit : hits) {
            String json = hit.getSourceAsString();
            ItemDoc itemDoc = JSONUtil.toBean(json, ItemDoc.class);

            // 设置高亮
            Map<String, HighlightField> hfs = hit.getHighlightFields();
            if(hfs != null && !hfs.isEmpty()){
                HighlightField hf = hfs.get("name");
                String hfName = hf.getFragments()[0].string();
                itemDoc.setName(hfName);
            }
            System.out.println("item info: " + itemDoc);

        }

    }

    @Test
    void testSearch() throws IOException {
        SearchRequest request = new SearchRequest("items");
        request.source().query(
                QueryBuilders.boolQuery()
                        .must(QueryBuilders.matchQuery("name", "脱脂牛奶"))
                        .filter(QueryBuilders.termQuery("brand", "德亚"))
                        .filter(QueryBuilders.rangeQuery("price").lt(30000))
        );
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);

        parseSource(response);
    }

    @Test
    void testSortAndPage() throws IOException {
        int pageNo = 1, pageSize = 5;

        SearchRequest request = new SearchRequest("items");
        // 查询条件
        request.source().query(
                QueryBuilders.matchAllQuery());
        // 排序条件
        request.source().sort("sold", SortOrder.DESC)
                        .sort("price", SortOrder.ASC);
        // 分页条件
        request.source().from((pageNo - 1) * pageSize).size(pageSize);

        SearchResponse response = client.search(request, RequestOptions.DEFAULT);

        parseSource(response);
    }

    @Test
    void testHighlight() throws IOException {
        SearchRequest request = new SearchRequest("items");
        // 查询条件
        request.source().query(
                QueryBuilders.matchQuery("name", "脱脂牛奶"));
        // 设置高亮
        request.source().highlighter(SearchSourceBuilder.highlight().field("name"));

        SearchResponse response = client.search(request, RequestOptions.DEFAULT);

        parseSource(response);
    }

    @Test
    void testAggregation() throws IOException {
        SearchRequest request = new SearchRequest("items");
        // 查询条件
        request.source().query(
                QueryBuilders.matchAllQuery()
        );
        request.source().size(0);
        // 聚合条件
        String aggName = "brandAgg";
        request.source().aggregation(
                AggregationBuilders.terms(aggName).field("brand").size(10)
        );

        SearchResponse response = client.search(request, RequestOptions.DEFAULT);

        // 解析查询结果
        Aggregations aggregations = response.getAggregations();
        Terms brandTerms = aggregations.get(aggName);
        List<? extends Terms.Bucket> buckets = brandTerms.getBuckets();
        for (Terms.Bucket bucket : buckets) {
            System.out.println("brand: " + bucket.getKeyAsString());
            System.out.println("count: " + bucket.getDocCount());
        }
    }

    @BeforeEach
    void setUp() {
        client = new RestHighLevelClient(
                RestClient.builder(
                        HttpHost.create("192.168.100.128:9200")
                )
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
    }
}
