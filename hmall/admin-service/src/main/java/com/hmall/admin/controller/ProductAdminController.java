package com.hmall.admin.controller;

import com.hmall.admin.feign.ItemFeignClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Api(tags = "商品管理接口")
@RestController
@RequestMapping("/admin/product")
@RequiredArgsConstructor
public class ProductAdminController {

    private final ItemFeignClient itemFeignClient;

    @ApiOperation("分页查询商品")
    @GetMapping("/list")
    public R<PageDTO<ItemDTO>> list(PageQuery pageQuery,
                                    @RequestParam(required = false) String keyword) {
        return R.ok(itemFeignClient.queryItemByPage(pageQuery));
    }

    @ApiOperation("商品详情")
    @GetMapping("/{id}")
    public R<ItemDTO> getInfo(@PathVariable Long id) {
        return itemFeignClient.queryItemById(id);
    }

    @ApiOperation("新增商品")
    @PostMapping
    public R<Void> create(@RequestBody ItemDTO item) {
        return itemFeignClient.saveItem(item);
    }

    @ApiOperation("更新商品")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody ItemDTO item) {
        item.setId(id);
        return itemFeignClient.updateItem(item);
    }

    @ApiOperation("批量上下架")
    @PostMapping("/publishStatus")
    public R<Void> updatePublishStatus(@RequestParam List<Long> ids,
                                        @RequestParam Integer publishStatus) {
        return itemFeignClient.batchUpdateStatus(ids, publishStatus);
    }

    @ApiOperation("批量删除商品")
    @DeleteMapping
    public R<Void> delete(@RequestParam List<Long> ids) {
        return itemFeignClient.batchDeleteItems(ids);
    }

    @ApiOperation("调整库存")
    @PutMapping("/stock/{id}")
    public R<Void> updateStock(@PathVariable Long id, @RequestParam Integer stock) {
        return itemFeignClient.batchUpdateStock(Map.of(id, stock));
    }
}
