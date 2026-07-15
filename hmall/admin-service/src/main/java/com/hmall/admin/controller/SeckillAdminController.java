package com.hmall.admin.controller;

import com.hmall.admin.feign.TradeFeignClient;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 秒杀管理接口（代理转发到 trade-service）
 * <p>
 * 路径前缀 /admin/seckill，对应 trade-service 的 /seckill/admin/** 接口。
 */
@Api(tags = "秒杀管理接口")
@RestController
@RequestMapping("/admin/seckill")
@RequiredArgsConstructor
public class SeckillAdminController {

    private final TradeFeignClient tradeFeignClient;

    // ==================== 活动管理 ====================

    @ApiOperation("分页查询秒杀活动")
    @GetMapping("/promotion/list")
    public R<PageDTO<Object>> promotionList(PageQuery pageQuery,
                                             @RequestParam(required = false) String title,
                                             @RequestParam(required = false) Integer status) {
        return R.ok(tradeFeignClient.queryPromotionPage(pageQuery, title, status));
    }

    @ApiOperation("秒杀活动详情")
    @GetMapping("/promotion/{id}")
    public R<Object> promotionDetail(@PathVariable Long id) {
        return R.ok(tradeFeignClient.getPromotionDetail(id));
    }

    @ApiOperation("创建秒杀活动")
    @PostMapping("/promotion")
    public R<Long> createPromotion(@RequestBody Object dto) {
        return R.ok(tradeFeignClient.createPromotion(dto));
    }

    @ApiOperation("修改秒杀活动")
    @PutMapping("/promotion")
    public R<Void> updatePromotion(@RequestBody Object dto) {
        tradeFeignClient.updatePromotion(dto);
        return R.ok();
    }

    @ApiOperation("删除秒杀活动")
    @DeleteMapping("/promotion/{id}")
    public R<Void> deletePromotion(@PathVariable Long id) {
        tradeFeignClient.deletePromotion(id);
        return R.ok();
    }

    // ==================== 场次管理 ====================

    @ApiOperation("分页查询秒杀场次")
    @GetMapping("/session/list")
    public R<PageDTO<Object>> sessionList(PageQuery pageQuery,
                                           @RequestParam(required = false) Long promotionId) {
        return R.ok(tradeFeignClient.querySessionPage(pageQuery, promotionId));
    }

    @ApiOperation("秒杀场次详情")
    @GetMapping("/session/{id}")
    public R<Object> sessionDetail(@PathVariable Long id) {
        return R.ok(tradeFeignClient.getSessionDetail(id));
    }

    @ApiOperation("创建秒杀场次")
    @PostMapping("/session")
    public R<Long> createSession(@RequestBody Object dto) {
        return R.ok(tradeFeignClient.createSession(dto));
    }

    @ApiOperation("修改秒杀场次")
    @PutMapping("/session")
    public R<Void> updateSession(@RequestBody Object dto) {
        tradeFeignClient.updateSession(dto);
        return R.ok();
    }

    @ApiOperation("删除秒杀场次")
    @DeleteMapping("/session/{id}")
    public R<Void> deleteSession(@PathVariable Long id) {
        tradeFeignClient.deleteSession(id);
        return R.ok();
    }

    // ==================== 商品关联管理 ====================

    @ApiOperation("分页查询秒杀商品关联")
    @GetMapping("/relation/list")
    public R<PageDTO<Object>> relationList(PageQuery pageQuery,
                                            @RequestParam(required = false) Long sessionId,
                                            @RequestParam(required = false) Long promotionId) {
        return R.ok(tradeFeignClient.queryRelationPage(pageQuery, sessionId, promotionId));
    }

    @ApiOperation("秒杀商品关联详情")
    @GetMapping("/relation/{id}")
    public R<Object> relationDetail(@PathVariable Long id) {
        return R.ok(tradeFeignClient.getRelationDetail(id));
    }

    @ApiOperation("创建秒杀商品关联")
    @PostMapping("/relation")
    public R<Long> createRelation(@RequestBody Object dto) {
        return R.ok(tradeFeignClient.createRelation(dto));
    }

    @ApiOperation("修改秒杀商品关联")
    @PutMapping("/relation")
    public R<Void> updateRelation(@RequestBody Object dto) {
        tradeFeignClient.updateRelation(dto);
        return R.ok();
    }

    @ApiOperation("删除秒杀商品关联")
    @DeleteMapping("/relation/{id}")
    public R<Void> deleteRelation(@PathVariable Long id) {
        tradeFeignClient.deleteRelation(id);
        return R.ok();
    }

    @ApiOperation("手动预热秒杀库存")
    @PostMapping("/relation/preheat/{id}")
    public R<Void> manualPreheat(@PathVariable Long id) {
        tradeFeignClient.manualPreheat(id);
        return R.ok();
    }

    // ==================== 秒杀订单管理 ====================

    @ApiOperation("分页查询秒杀订单")
    @GetMapping("/order/list")
    public R<PageDTO<Object>> orderList(PageQuery pageQuery,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false) Long relationId,
                                         @RequestParam(required = false) Long userId) {
        return R.ok(tradeFeignClient.querySeckillOrderPage(pageQuery, status, relationId, userId));
    }

    // ==================== 库存查询 ====================

    @ApiOperation("查询秒杀商品库存状态")
    @GetMapping("/stock/{relationId}")
    public R<List<Object>> stockStatus(@PathVariable Long relationId) {
        return R.ok(tradeFeignClient.queryStockStatus(relationId));
    }
}
