package com.hmall.search.controller;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmall.common.domain.PageDTO;

import com.hmall.search.domain.vo.CategoryBrandVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import com.hmall.search.domain.dto.ItemDTO;
import com.hmall.search.domain.po.Item;
import com.hmall.search.domain.query.ItemPageQuery;
import com.hmall.search.service.ISearchService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Api(tags = "搜索相关接口")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final ISearchService searchService;

//    @ApiOperation("搜索商品")
//    @GetMapping("/list")
//    public PageDTO<ItemDTO> search(ItemPageQuery query) {
//        // 分页查询
//        Page<Item> result = searchService.lambdaQuery()
//                .like(StrUtil.isNotBlank(query.getKey()), Item::getName, query.getKey())
//                .eq(StrUtil.isNotBlank(query.getBrand()), Item::getBrand, query.getBrand())
//                .eq(StrUtil.isNotBlank(query.getCategory()), Item::getCategory, query.getCategory())
//                .eq(Item::getStatus, 1)
//                .between(query.getMaxPrice() != null, Item::getPrice, query.getMinPrice(), query.getMaxPrice())
//                .page(query.toMpPage("update_time", false));
//        // 封装并返回
//        return PageDTO.of(result, ItemDTO.class);
//    }

    @ApiOperation("搜索商品")
    @GetMapping("/list")
    public PageDTO<ItemDTO> search(ItemPageQuery query) throws IOException {
        Page<ItemDTO> result = searchService.search(query);
        return PageDTO.of(result);
    }
    @ApiOperation("过滤条件")
    @PostMapping("/filters")
    public Map filters(@RequestBody ItemPageQuery query) throws IOException {
        log.info("过滤商品，查询条件：{}", JSONUtil.toJsonStr(query));
        CategoryBrandVO categoryBrandVO = searchService.filters(query);
        return categoryBrandVO.getResultMap();
    }

    @GetMapping("/empty")
    public void empty() {
        return;
    }

    @ApiOperation("推荐商品召回")
    @GetMapping("/recommend")
    public List<ItemDTO> recommend(
            @RequestParam(value = "categories", required = false) List<String> categories,
            @RequestParam(value = "excludeIds", required = false) List<Long> excludeIds,
            @RequestParam("size") Integer size
    ) throws IOException {
        return searchService.recommendSearch(categories, excludeIds, size);
    }
}
