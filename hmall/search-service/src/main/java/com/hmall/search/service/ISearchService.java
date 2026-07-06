package com.hmall.search.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmall.search.domain.dto.ItemDTO;
import com.hmall.search.domain.po.Item;
import com.hmall.search.domain.query.ItemPageQuery;
import com.hmall.search.domain.vo.CategoryBrandVO;

import java.io.IOException;

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
}
