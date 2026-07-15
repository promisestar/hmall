package com.hmall.admin.feign;

import com.hmall.admin.feign.fallback.ItemFeignFallbackFactory;
import com.hmall.api.config.DefaultFeignConfig;
import com.hmall.api.dto.ItemDTO;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
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
    ItemDTO queryItemById(@PathVariable Long id);

    @PostMapping("/items")
    void saveItem(@RequestBody ItemDTO item);

    @PutMapping("/items")
    void updateItem(@RequestBody ItemDTO item);

    @DeleteMapping("/items/{id}")
    void deleteItemById(@PathVariable Long id);

    @PutMapping("/items/batch/status")
    void batchUpdateStatus(@RequestParam List<Long> ids, @RequestParam Integer status);

    @PutMapping("/items/batch/stock")
    void batchUpdateStock(@RequestBody Map<Long, Integer> stockMap);

    @DeleteMapping("/items/batch")
    void batchDeleteItems(@RequestParam List<Long> ids);
}
