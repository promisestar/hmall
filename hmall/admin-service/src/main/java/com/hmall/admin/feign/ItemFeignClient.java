package com.hmall.admin.feign;

import com.hmall.admin.feign.fallback.ItemFeignFallbackFactory;
import com.hmall.api.config.DefaultFeignConfig;
import com.hmall.api.dto.ItemDTO;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(name = "item-service", contextId = "admin-item",
        configuration = DefaultFeignConfig.class,
        fallbackFactory = ItemFeignFallbackFactory.class)
public interface ItemFeignClient {

    @GetMapping("/items/page")
    PageDTO<ItemDTO> queryItemByPage(@SpringQueryMap PageQuery pageQuery);

    @GetMapping("/items/{id}")
    R<ItemDTO> queryItemById(@PathVariable Long id);

    @PostMapping("/items")
    R<Void> saveItem(@RequestBody ItemDTO item);

    @PutMapping("/items")
    R<Void> updateItem(@RequestBody ItemDTO item);

    @DeleteMapping("/items/{id}")
    R<Void> deleteItemById(@PathVariable Long id);

    @PutMapping("/items/batch/status")
    R<Void> batchUpdateStatus(@RequestParam List<Long> ids, @RequestParam Integer status);

    @PutMapping("/items/batch/stock")
    R<Void> batchUpdateStock(@RequestBody Map<Long, Integer> stockMap);

    @DeleteMapping("/items/batch")
    R<Void> batchDeleteItems(@RequestParam List<Long> ids);
}
