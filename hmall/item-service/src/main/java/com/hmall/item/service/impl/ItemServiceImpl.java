package com.hmall.item.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.service.RedisService;
import com.hmall.common.utils.BeanUtils;

import com.hmall.item.domain.dto.ItemDTO;
import com.hmall.item.domain.dto.OrderDetailDTO;
import com.hmall.item.domain.po.Item;
import com.hmall.item.mapper.ItemMapper;
import com.hmall.item.service.IItemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 商品表 服务实现类
 * </p>
 *
 * @author 虎哥
 */
@Slf4j
@Service
public class ItemServiceImpl extends ServiceImpl<ItemMapper, Item> implements IItemService {

    @Autowired
    private RedisService redisService;

    private static final String ITEM_CACHE_PREFIX = "item:info:";
    private static final long ITEM_CACHE_TTL_MINUTES = 30;

    @Override
    @Transactional
    public void deductStock(List<OrderDetailDTO> items) {
        String sqlStatement = "com.hmall.item.mapper.ItemMapper.updateStock";
        boolean r = false;
        try {
            r = executeBatch(items, (sqlSession, entity) -> sqlSession.update(sqlStatement, entity));
        } catch (Exception e) {
            throw new BizIllegalException("更新库存异常，可能是库存不足!", e);
        }
        if (!r) {
            throw new BizIllegalException("库存不足！");
        }
    }

    @Override
    @Transactional
    public void recoverStock(List<OrderDetailDTO> items) {
        String sqlStatement = "com.hmall.item.mapper.ItemMapper.updateStock";
        boolean r = false;
        try {
            r = executeBatch(items, (sqlSession, entity) -> sqlSession.update(sqlStatement, entity));
        } catch (Exception e) {
            throw new BizIllegalException("更新库存异常，可能是库存不足!", e);
        }
        if (!r) {
            throw new BizIllegalException("库存不足！");
        }
    }

    @Override
    public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
        try {
            List<ItemDTO> result = new ArrayList<>(ids.size());
            List<Long> missedIds = new ArrayList<>();

            // 1. 先批量查 Redis 缓存
            for (Long id : ids) {
                try {
                    String cacheKey = ITEM_CACHE_PREFIX + id;
                    ItemDTO cached = redisService.get(cacheKey, ItemDTO.class);
                    if (cached != null) {
                        result.add(cached);
                    } else {
                        missedIds.add(id);
                    }
                } catch (Exception e) {
                    // Redis 单条查询失败，降级到 MySQL
                    log.warn("Redis 查询商品缓存失败，id={}", id, e);
                    missedIds.add(id);
                }
            }

            // 2. 未命中的去 MySQL 查
            if (!missedIds.isEmpty()) {
                List<Item> dbItems = listByIds(missedIds);
                List<ItemDTO> dbDTOs = BeanUtils.copyList(dbItems, ItemDTO.class);
                result.addAll(dbDTOs);

                // 3. 回写 Redis 缓存（SET NX EX，避免覆盖已被删除/刷新的缓存）
                for (ItemDTO dto : dbDTOs) {
                    try {
                        String cacheKey = ITEM_CACHE_PREFIX + dto.getId();
                        redisService.setIfAbsent(cacheKey, dto, ITEM_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        log.warn("Redis 回写商品缓存失败，id={}", dto.getId(), e);
                    }
                }
            }

            return result;
        } catch (Exception e) {
            log.error("查询商品信息失败，ids={}", ids, e);
            throw new BizIllegalException("查询商品信息异常", e);
        }
    }
}
