package com.hmall.search.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.common.utils.BeanUtils;
import com.hmall.common.utils.CollUtils;
import com.hmall.search.domain.dto.ItemDTO;
import com.hmall.search.domain.dto.ItemDoc;
import com.hmall.search.domain.po.Item;
import com.hmall.search.domain.query.ItemPageQuery;
import com.hmall.search.domain.vo.CategoryBrandVO;
import com.hmall.search.mapper.SearchMapper;
import com.hmall.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.common.lucene.search.function.FunctionScoreQuery;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.index.query.functionscore.WeightBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.naming.directory.SearchResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ClassName: SearchServiceImpl
 * Package: search.service
 * Description:
 *
 * @Author Raiden
 * @Create 2026/1/9 09:17
 * @Version 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl extends ServiceImpl<SearchMapper, Item> implements ISearchService {
    private final RestHighLevelClient restHighLevelClient;

    public Page<ItemDTO> search(ItemPageQuery query) throws IOException {
        int pageNo = query.getPageNo(), pageSize = query.getPageSize();
        SearchRequest searchRequest = new SearchRequest("items");
        // 构建查询条件
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        if (StringUtils.hasText(query.getKey())) {
            boolQueryBuilder.must(QueryBuilders.matchQuery("name", query.getKey()));
        }
        if (StringUtils.hasText(query.getBrand())) {
            boolQueryBuilder.filter(QueryBuilders.termQuery("brand", query.getBrand()));
        }
        if (StringUtils.hasText(query.getCategory())) {
            boolQueryBuilder.filter(QueryBuilders.termQuery("category", query.getCategory()));
        }
        if (query.getMaxPrice() != null) {
            RangeQueryBuilder rangeQueryBuilder = QueryBuilders.rangeQuery("price");
            if (query.getMinPrice() != null) {
                rangeQueryBuilder.gte(query.getMinPrice());
            }
            rangeQueryBuilder.lte(query.getMaxPrice());
            boolQueryBuilder.filter(rangeQueryBuilder);
        }
        // 为isAD为true的商品分数加上固定的常量值
        FunctionScoreQueryBuilder.FilterFunctionBuilder[] functions = {
                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                        QueryBuilders.termQuery("isAD", true),
                        ScoreFunctionBuilders.weightFactorFunction(100f)
                )};
        // 使用算分函数
        FunctionScoreQueryBuilder functionScoreQuery = QueryBuilders.functionScoreQuery(boolQueryBuilder, functions)
                .boostMode(CombineFunction.MULTIPLY)// 加上固定权重
                .scoreMode(FunctionScoreQuery.ScoreMode.SUM);// 多个算分函数的合并方法

        searchSourceBuilder.query(functionScoreQuery);
        searchSourceBuilder.from((pageNo - 1) * pageSize).size(pageSize);
        searchSourceBuilder.sort("_score", SortOrder.DESC);
        searchSourceBuilder.sort(new FieldSortBuilder("update_time").order(SortOrder.DESC));
        searchSourceBuilder.timeout(new TimeValue(60, TimeUnit.SECONDS));
        searchRequest.source(searchSourceBuilder);

        SearchResponse response = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
        // 解析响应结果
        SearchHits searchHits = response.getHits();
        // 解析总条数
        long total = searchHits.getTotalHits().value;

        // 解析结果数据
        SearchHit[] hits = searchHits.getHits();
        List<ItemDTO> itemList = new ArrayList<>();
        for (SearchHit hit : hits) {
            String json = hit.getSourceAsString();
            ItemDoc itemDoc = JSONUtil.toBean(json, ItemDoc.class);
            ItemDTO itemDTO = BeanUtils.copyProperties(itemDoc, ItemDTO.class);
            itemList.add(itemDTO);
        }
        Page<ItemDTO> page = new Page<>(pageNo, pageSize, total);
        page.setRecords(itemList);
        return page;
    }

    @Override
    public void updateDocument(ItemDTO item) throws IOException {
        // 1. 转换为文档实体
        ItemDoc itemDoc = BeanUtils.copyProperties(item, ItemDoc.class);

        // 2. 构建增量更新的字段Map（只更新有值/需要修改的字段，而非全量）
        Map<String, Object> updateFields = new HashMap<>();
        if (itemDoc.getName() != null) {
            updateFields.put("name", itemDoc.getName());
        }
        if (itemDoc.getBrand() != null) {
            updateFields.put("brand", itemDoc.getBrand());
        }
        if (itemDoc.getImage() != null) {
            updateFields.put("image", itemDoc.getImage());
        }
        if (itemDoc.getSold() != null) {
            updateFields.put("sold", itemDoc.getSold());
        }
        if (itemDoc.getCategory() != null) {
            updateFields.put("category", itemDoc.getCategory());
        }
        if (itemDoc.getCommentCount() != null) {
            updateFields.put("commentCount", itemDoc.getCommentCount());
        }
        if (itemDoc.getPrice() != null) {
            updateFields.put("price", itemDoc.getPrice()); // 保持数值类型，避免序列化问题
        }
        if (itemDoc.getIsAD() != null){
            updateFields.put("isAD", itemDoc.getIsAD());
        }
        if (itemDoc.getUpdateTime() != null) {
            updateFields.put("update_time", itemDoc.getUpdateTime());
        }
        // 3. 构建UpdateRequest
        UpdateRequest request = new UpdateRequest(
                "items",          // 索引名
                itemDoc.getId()  // 文档ID（确保为字符串，ES文档ID默认是字符串）
        );

        // 核心配置1：增量更新字段（避免全量覆盖）
        request.doc(updateFields);
        // 核心配置2：文档不存在时，自动用当前字段创建新文档（可选，根据业务需求）
        request.docAsUpsert(true);
        // 配置3：设置超时时间（避免长时间阻塞）
        request.timeout("10s");
        // 4. 执行更新并处理响应
        try {
            UpdateResponse response = restHighLevelClient.update(request, RequestOptions.DEFAULT);
            log.info("ES文档更新成功，文档ID：{}，是否创建新文档：{}",
                    itemDoc.getId(), response.getResult().name().equals("CREATED"));
        } catch (Exception e) {
            log.error("ES文档更新失败，文档ID：{}", itemDoc.getId(), e);
            throw new IOException("更新ES文档失败", e);
        }
    }

    @Override
    public void removeDocumentById(Long id) throws IOException {
        // 1. 构建删除文档请求
        DeleteRequest request = new DeleteRequest("items", id.toString());
        // 2. 执行更新并处理响应
        try {
            DeleteResponse Response = restHighLevelClient.delete(request, RequestOptions.DEFAULT);
            log.info("ES文档删除成功，文档ID：{}", id);
        } catch (Exception e) {
            log.error("ES文档删除失败，文档ID：{}", id, e);
            throw new IOException("删除ES文档失败", e);
        }

    }

    @Override
    public void createDocument(ItemDTO item) throws IOException {
        ItemDoc itemDoc = BeanUtils.copyProperties(item, ItemDoc.class);
        // 1. 构建创建文档请求
        IndexRequest request = new IndexRequest("items").id(itemDoc.getId());
        // 2. 填充数据
        request.source(JSONUtil.toJsonStr(itemDoc), XContentType.JSON);
        // 3. 发送请求并输出日志
        try{
            IndexResponse response = restHighLevelClient.index(request, RequestOptions.DEFAULT);
            log.info("ES文档创建成功，文档ID：{}", itemDoc.getId());
        }catch (Exception e){
            log.error("ES文档创建失败，文档ID：{}", itemDoc.getId(), e);
            throw new IOException("创建ES文档失败", e);
        }
    }

    @Override
    public CategoryBrandVO filters(ItemPageQuery query) throws IOException {
        SearchRequest searchRequest = new SearchRequest("items");
        // 构建查询条件
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        if (StringUtils.hasText(query.getKey())) {
            boolQueryBuilder.must(QueryBuilders.matchQuery("name", query.getKey()));
        }
        if (StringUtils.hasText(query.getBrand())) {
            boolQueryBuilder.filter(QueryBuilders.termQuery("brand", query.getBrand()));
        }
        if (StringUtils.hasText(query.getCategory())) {
            boolQueryBuilder.filter(QueryBuilders.termQuery("category", query.getCategory()));
        }
        if (query.getMaxPrice() != null) {
            RangeQueryBuilder rangeQueryBuilder = QueryBuilders.rangeQuery("price");
            if (query.getMinPrice() != null) {
                rangeQueryBuilder.gte(query.getMinPrice());
            }
            rangeQueryBuilder.lte(query.getMaxPrice());
            boolQueryBuilder.filter(rangeQueryBuilder);
        }
        searchSourceBuilder.query(boolQueryBuilder);
        searchSourceBuilder.size(0);
        searchRequest.source(searchSourceBuilder);
        // 2. 聚合条件
        TermsAggregationBuilder brandAgg = AggregationBuilders.terms("brandAgg").field("brand");
        TermsAggregationBuilder categoryAgg = AggregationBuilders.terms("categoryAgg").field("category");
        searchRequest.source().aggregation(brandAgg);
        searchRequest.source().aggregation(categoryAgg);

        SearchResponse response = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
        Aggregations aggregations = response.getAggregations();

        CategoryBrandVO categoryBrandVO = new CategoryBrandVO();
        System.out.println("===== 品牌聚合结果 =====");
        Terms brandTerms = aggregations.get("brandAgg");
        List<? extends Terms.Bucket> brandBuckets = brandTerms.getBuckets();
        for (Terms.Bucket bucket : brandBuckets) {
            String brand = bucket.getKeyAsString();
            categoryBrandVO.getResultMap().get("brand").add(brand);
            long count = bucket.getDocCount();
            System.out.printf("品牌：%s，数量：%d%n", brand, count);
        }

        // 5.2 解析分类聚合结果（新增）
        System.out.println("\n===== 分类聚合结果 =====");
        Terms categoryTerms = aggregations.get("categoryAgg");
        List<? extends Terms.Bucket> categoryBuckets = categoryTerms.getBuckets();
        for (Terms.Bucket bucket : categoryBuckets) {
            String category = bucket.getKeyAsString();
            categoryBrandVO.getResultMap().get("category").add(category);
            long count = bucket.getDocCount();
            System.out.printf("分类：%s，数量：%d%n", category, count);
        }
        return categoryBrandVO;
    }
}
