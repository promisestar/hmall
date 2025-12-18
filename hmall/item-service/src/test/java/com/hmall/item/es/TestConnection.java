package com.hmall.item.es;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmall.item.domain.dto.ItemDoc;
import com.hmall.item.domain.po.Item;
import com.hmall.item.service.IItemService;
import org.apache.http.HttpHost;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.xcontent.XContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.io.IOException;
import java.util.List;

/**
 * ClassName: TestConnection
 * Package: com.hmall.item.es
 * Description:
 *
 * @Author Raiden
 * @Create 2025/12/1 19:42
 * @Version 1.0
 */
@SpringBootTest(properties = "spring.profiles.active=local")
public class TestConnection{

    private RestHighLevelClient client;

    @Autowired
    private IItemService iItemService;

    @Test
    void testConnection() {
        System.out.println("client = " + client);
    }

    @Test
    void testIndexDocument() throws IOException {
        Item item = iItemService.getById(317578L);
        ItemDoc itemDoc = BeanUtil.copyProperties(item, ItemDoc.class);
        itemDoc.setPrice(10);
        // 1. request请求
        IndexRequest request = new IndexRequest("items").id(itemDoc.getId());
        // 2. 数据
        request.source(JSONUtil.toJsonStr(itemDoc), XContentType.JSON);
        // 3. 发送请求
        client.index(request, RequestOptions.DEFAULT);
    }
    @Test
    void testGetDocument() throws IOException {
        GetRequest request = new GetRequest("items", "317578");
        GetResponse item = client.get(request, RequestOptions.DEFAULT);
        String info = item.getSourceAsString();
        ItemDoc itemDoc = JSONUtil.toBean(info, ItemDoc.class);
        System.out.println("item info: " + itemDoc);
    }
    @Test
    void testDeleteDocument() throws IOException {
        DeleteRequest request = new DeleteRequest("items", "317578");
        client.delete(request, RequestOptions.DEFAULT);
    }
    @Test
    void testUpdataDocument() throws IOException {
        UpdateRequest request = new UpdateRequest("items", "317578");
        request.doc(
                "price", 20
        );
        client.update(request, RequestOptions.DEFAULT);
    }
    @Test
    void testBulkDocument() throws IOException {
        int pageNo = 1, pageSize = 500;
        while(true) {
            // 0. 准备数据
            Page<Item> page = iItemService.lambdaQuery()
                    .eq(Item::getStatus, 1)
                    .page(Page.of(pageNo, pageSize));
            List<Item> records = page.getRecords();
            if (records == null || records.isEmpty()) {
                return;
            }
            BulkRequest request = new BulkRequest();
            for (Item record : records) {
                request.
                        add(new IndexRequest("items").
                                id(record.getId().toString()).
                                source(JSONUtil.toJsonStr(BeanUtil.copyProperties(record, ItemDoc.class)), XContentType.JSON));
            }
            client.bulk(request, RequestOptions.DEFAULT);
            pageNo++;
        }
    }

    @Test
    void testCreateIndex() throws IOException {
        // 1. 准备request对象
        CreateIndexRequest request = new CreateIndexRequest("items");
        // 2. 准备请求参数
        request.source(MAPPING_TEMPLATE, XContentType.JSON);
        // 3. 发送请求
        client.indices().create(request, RequestOptions.DEFAULT);
    }

    @Test
    void testGetIndex() throws IOException {
        // 1. 准备request对象
        GetIndexRequest request = new GetIndexRequest("items");
        // 2. 发送请求
        boolean exists = client.indices().exists(request, RequestOptions.DEFAULT);
        System.out.println("exists = " + exists);
    }
    @Test
    void testDeleteIndex() throws IOException {
        // 1. 准备request对象
        DeleteIndexRequest request = new DeleteIndexRequest("items");
        // 2. 发送请求
        client.indices().delete(request, RequestOptions.DEFAULT);
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

    private static final String MAPPING_TEMPLATE = "{\n" +
            "  \"mappings\": {\n" +
            "    \"properties\": {\n" +
            "      \"id\":{\n" +
            "        \"type\": \"keyword\"\n" +
            "      },\n" +
            "      \"name\":{\n" +
            "        \"type\": \"text\",\n" +
            "        \"analyzer\": \"ik_smart\"\n" +
            "      },\n" +
            "      \"price\":{\n" +
            "        \"type\": \"integer\"\n" +
            "      },\n" +
            "      \"image\":{\n" +
            "        \"type\": \"keyword\",\n" +
            "        \"index\": false\n" +
            "      },\n" +
            "      \"category\":{\n" +
            "        \"type\": \"keyword\"\n" +
            "      },\n" +
            "      \"brand\":{\n" +
            "        \"type\": \"keyword\"\n" +
            "      },\n" +
            "      \"sold\":{\n" +
            "        \"type\": \"integer\"\n" +
            "      },\n" +
            "      \"comment_count\":{\n" +
            "        \"type\": \"integer\",\n" +
            "        \"index\": false\n" +
            "      },\n" +
            "      \"isAD\":{\n" +
            "        \"type\": \"boolean\"\n" +
            "      },\n" +
            "      \"update_time\":{\n" +
            "        \"type\": \"date\"\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";
}