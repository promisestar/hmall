package com.hmall.search.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmall.search.domain.dto.ItemDTO;
import com.hmall.search.domain.po.Item;
import com.hmall.search.domain.query.ItemPageQuery;
import com.hmall.search.domain.vo.CategoryBrandVO;

import java.io.IOException;
import java.util.List;

/**
 * ClassName: ISearchService
 * Package: search.service
 * Description:
 *
 * @Author Raiden
 * @Create 2026/1/9 09:16
 * @Version 1.0
 */
public interface ISearchService extends IService<Item> {
    Page<ItemDTO> search(ItemPageQuery query) throws IOException;

    void updateDocument(ItemDTO item) throws IOException;

    void removeDocumentById(Long id) throws IOException;

    void createDocument(ItemDTO item) throws IOException;

    CategoryBrandVO filters(ItemPageQuery query) throws IOException;

    /**
     * 推荐商品 ES 召回：按类目过滤 + 排除已购 + 销量排序
     *
     * @param categories  偏好类目列表（为空时走热销兜底 matchAll）
     * @param excludeIds  需排除的商品 ID 列表（已购商品）
     * @param size        返回数量
     * @return            商品列表（stock/status 字段为空，需调用方补充）
     */
    List<ItemDTO> recommendSearch(List<String> categories, List<Long> excludeIds, Integer size) throws IOException;
}
