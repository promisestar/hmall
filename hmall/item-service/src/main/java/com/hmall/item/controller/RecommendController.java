package com.hmall.item.controller;

import com.hmall.common.utils.UserContext;
import com.hmall.item.domain.dto.RecommendVO;
import com.hmall.item.service.IRecommendService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个性化推荐 REST API
 * <p>
 * 端点：GET /recommend?scene=home&size=10&itemId=1001
 * <p>
 * 需要登录（Gateway 认证后通过 user-info header 传递 userId）。
 */
@Slf4j
@Api(tags = "个性化推荐接口")
@RestController
@RequestMapping("/recommend")
@RequiredArgsConstructor
public class RecommendController {

    private final IRecommendService recommendService;

    @ApiOperation("获取个性化推荐商品")
    @GetMapping
    public RecommendVO recommend(
            @ApiParam("推荐场景：home/detail/cart") @RequestParam(defaultValue = "home") String scene,
            @ApiParam("返回数量") @RequestParam(defaultValue = "10") Integer size,
            @ApiParam("种子商品ID（scene=detail时必填）") @RequestParam(required = false) Long itemId
    ) {
        Long userId = UserContext.getUser();
        return recommendService.recommend(userId, scene, size, itemId);
    }
}
