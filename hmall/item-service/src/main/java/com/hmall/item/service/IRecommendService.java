package com.hmall.item.service;

import com.hmall.item.domain.dto.RecommendVO;

/**
 * 个性化推荐服务接口
 */
public interface IRecommendService {

    /**
     * 获取个性化推荐商品
     *
     * @param userId  用户 ID
     * @param scene   推荐场景：home/detail/cart
     * @param size    返回数量
     * @param itemId  种子商品 ID（scene=detail 时必填）
     * @return        推荐响应
     */
    RecommendVO recommend(Long userId, String scene, Integer size, Long itemId);
}
