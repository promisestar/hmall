package com.hmall.item.controller;


import cn.hutool.core.thread.ThreadUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.service.RedisService;
import com.hmall.common.utils.BeanUtils;
import com.hmall.item.constants.MQConstants;
import com.hmall.item.domain.dto.ItemDTO;
import com.hmall.item.domain.dto.OrderDetailDTO;
import com.hmall.item.domain.po.Item;
import com.hmall.item.mq.ItemCacheSender;
import com.hmall.item.service.IItemService;
import com.hmall.item.task.ItemCacheCompensationTask;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@Api(tags = "商品管理相关接口")
@Slf4j
@RestController
@RequestMapping("/items")
@RequiredArgsConstructor
public class ItemController {

    private final IItemService itemService;
    private final RabbitTemplate rabbitTemplate;
    private final RedisService redisService;
    private final ItemCacheSender itemCacheSender;
    private final ItemCacheCompensationTask cacheCompensationTask;

    private static final String ITEM_CACHE_PREFIX = "item:info:";

    @ApiOperation("分页查询商品")
    @GetMapping("/page")
    public PageDTO<ItemDTO> queryItemByPage(PageQuery query) {
        // 1.分页查询
        Page<Item> result = itemService.page(query.toMpPage("update_time", false));
        // 2.封装并返回
        return PageDTO.of(result, ItemDTO.class);
    }

    @ApiOperation("根据id批量查询商品")
    @GetMapping
    public List<ItemDTO> queryItemByIds(@RequestParam("ids") List<Long> ids){
        // 模拟业务延迟
        // ThreadUtil.sleep(500);
        return itemService.queryItemByIds(ids);
    }

    @ApiOperation("根据id查询商品")
    @GetMapping("{id}")
    public ItemDTO queryItemById(@PathVariable("id") Long id) {
        return BeanUtils.copyBean(itemService.getById(id), ItemDTO.class);
    }

    @ApiOperation("新增商品")
    @PostMapping
    public void saveItem(@RequestBody @Valid ItemDTO item) {
        // 新增
        itemService.save(BeanUtils.copyBean(item, Item.class));
        try {
            rabbitTemplate.convertAndSend(MQConstants.SEARCH_EXCHANGE_NAME, MQConstants.CREATE_DOCUMENT_KEY, item);
        } catch (Exception e) {
            log.error("同步ES新增商品失败，itemId={}", item.getId(), e);
        }
    }

    @ApiOperation("更新商品状态")
    @PutMapping("/status/{id}/{status}")
    public void updateItemStatus(@PathVariable("id") Long id, @PathVariable("status") Integer status){
        Item item = new Item();
        item.setId(id);
        item.setStatus(status);
        itemService.updateById(item);
        Item updated_item = itemService.getById(id);
        try {
            rabbitTemplate.convertAndSend(MQConstants.SEARCH_EXCHANGE_NAME, MQConstants.UPDATE_DOCUMENT_KEY,
                    BeanUtils.copyBean(updated_item, ItemDTO.class));
        } catch (Exception e) {
            log.error("同步ES更新商品状态失败，itemId={}", id, e);
        }
        // 主动删除缓存，下次查询时重新加载
        deleteItemCache(id);
        itemCacheSender.sendInvalidate(id);
        cacheCompensationTask.markDirty(id);
    }

    @ApiOperation("更新商品")
    @PutMapping
    public void updateItem(@RequestBody @Valid ItemDTO item) {
        // 不允许修改商品状态，所以强制设置为null，更新时，就会忽略该字段
        item.setStatus(null);
        // 更新
        itemService.updateById(BeanUtils.copyBean(item, Item.class));
        try {
            rabbitTemplate.convertAndSend(MQConstants.SEARCH_EXCHANGE_NAME, MQConstants.UPDATE_DOCUMENT_KEY, item);
        } catch (Exception e) {
            log.error("同步ES更新商品失败，itemId={}", item.getId(), e);
        }
        // 主动删除缓存
        deleteItemCache(item.getId());
        itemCacheSender.sendInvalidate(item.getId());
        cacheCompensationTask.markDirty(item.getId());
    }

    @ApiOperation("根据id删除商品")
    @DeleteMapping("{id}")
    public void deleteItemById(@PathVariable("id") Long id) {
        itemService.removeById(id);
        try {
            rabbitTemplate.convertAndSend(MQConstants.SEARCH_EXCHANGE_NAME, MQConstants.REMOVE_DOCUMENT_KEY, id);
        } catch (Exception e) {
            log.error("同步ES删除商品失败，itemId={}", id, e);
        }
        // 主动删除缓存
        deleteItemCache(id);
        itemCacheSender.sendInvalidate(id);
        cacheCompensationTask.markDirty(id);
    }

    @ApiOperation("批量扣减库存")
    @PutMapping("/stock/deduct")
    public void deductStock(@RequestBody List<OrderDetailDTO> items){
        itemService.deductStock(items);
    }

    // ==================== 管理后台接口（admin-service 调用） ====================

    @ApiOperation("批量修改商品状态(上下架)")
    @PutMapping("/batch/status")
    public void batchUpdateStatus(@RequestParam List<Long> ids, @RequestParam Integer status) {
        for (Long id : ids) {
            Item item = new Item();
            item.setId(id);
            item.setStatus(status);
            itemService.updateById(item);
            // 同步 ES 缓存
            Item updated = itemService.getById(id);
            try {
                rabbitTemplate.convertAndSend(MQConstants.SEARCH_EXCHANGE_NAME, MQConstants.UPDATE_DOCUMENT_KEY,
                        BeanUtils.copyBean(updated, ItemDTO.class));
            } catch (Exception e) {
                log.error("同步ES更新商品状态失败, itemId={}", id, e);
            }
            deleteItemCache(id);
            itemCacheSender.sendInvalidate(id);
            cacheCompensationTask.markDirty(id);
        }
    }

    @ApiOperation("批量修改商品库存")
    @PutMapping("/batch/stock")
    public void batchUpdateStock(@RequestBody Map<Long, Integer> stockMap) {
        for (Map.Entry<Long, Integer> entry : stockMap.entrySet()) {
            Item item = new Item();
            item.setId(entry.getKey());
            item.setStock(entry.getValue());
            itemService.updateById(item);
            deleteItemCache(entry.getKey());
            itemCacheSender.sendInvalidate(entry.getKey());
        }
    }

    @ApiOperation("批量删除商品(逻辑删除)")
    @DeleteMapping("/batch")
    public void batchDelete(@RequestParam List<Long> ids) {
        for (Long id : ids) {
            itemService.removeById(id);
            try {
                rabbitTemplate.convertAndSend(MQConstants.SEARCH_EXCHANGE_NAME, MQConstants.REMOVE_DOCUMENT_KEY, id);
            } catch (Exception e) {
                log.error("同步ES删除商品失败, itemId={}", id, e);
            }
            deleteItemCache(id);
            itemCacheSender.sendInvalidate(id);
            cacheCompensationTask.markDirty(id);
        }
    }

    @ApiOperation("恢复库存")
    @PutMapping("/stock/recover")
    public void recoverStock(@RequestBody List<OrderDetailDTO> items){
        itemService.recoverStock(items);
    }

    /**
     * 删除商品缓存（Redis 不可用时忽略异常，下次查询自动重建）
     */
    private void deleteItemCache(Long itemId) {
        try {
            redisService.delete(ITEM_CACHE_PREFIX + itemId);
        } catch (Exception e) {
            log.warn("删除商品缓存失败，itemId={}", itemId, e);
        }
    }
}
