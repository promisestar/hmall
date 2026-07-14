package com.hmall.trade.controller;

import com.hmall.trade.domain.vo.SeckillActivityVO;
import com.hmall.trade.domain.vo.SeckillProductVO;
import com.hmall.trade.domain.vo.SeckillResultVO;
import com.hmall.trade.service.SeckillService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 秒杀 REST API
 * <p>
 * 端点说明：
 * <ul>
 *   <li>GET  /seckill/activities — 查询秒杀活动列表（含场次、商品）</li>
 *   <li>GET  /seckill/products/{relationId} — 查询秒杀商品详情</li>
 *   <li>POST /seckill/order/{relationId} — 秒杀下单（限流保护）</li>
 *   <li>GET  /seckill/result/{relationId} — 轮询秒杀订单结果</li>
 * </ul>
 * <p>
 * Gateway 对 /seckill/** 路径启用滑动窗口限流（每用户 5 秒 1 次）。
 */
@Api(tags = "秒杀接口")
@RestController
@RequestMapping("/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;

    @ApiOperation("查询秒杀活动列表")
    @GetMapping("/activities")
    public List<SeckillActivityVO> queryActivities() {
        return seckillService.queryActivities();
    }

    @ApiOperation("查询秒杀商品详情")
    @ApiImplicitParam(name = "relationId", value = "商品关联ID", paramType = "path")
    @GetMapping("/products/{relationId}")
    public SeckillProductVO queryProduct(@PathVariable("relationId") Long relationId) {
        return seckillService.queryProduct(relationId);
    }

    @ApiOperation("秒杀下单")
    @ApiImplicitParam(name = "relationId", value = "商品关联ID", paramType = "path")
    @PostMapping("/order/{relationId}")
    public SeckillResultVO doSeckill(@PathVariable("relationId") Long relationId,
                                      @RequestParam(value = "quantity", defaultValue = "1") Integer quantity) {
        return seckillService.doSeckill(relationId, quantity);
    }

    @ApiOperation("轮询秒杀订单结果")
    @ApiImplicitParam(name = "relationId", value = "商品关联ID", paramType = "path")
    @GetMapping("/result/{relationId}")
    public SeckillResultVO getOrderResult(@PathVariable("relationId") Long relationId) {
        return seckillService.getOrderResult(relationId);
    }
}
