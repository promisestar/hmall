package com.hmall.trade.controller;

import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.trade.domain.dto.SeckillProductRelationDTO;
import com.hmall.trade.domain.dto.SeckillPromotionDTO;
import com.hmall.trade.domain.dto.SeckillSessionDTO;
import com.hmall.trade.domain.vo.*;
import com.hmall.trade.service.SeckillService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
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
 * 管理后台接口（/seckill/admin/**）：活动/场次/商品关联 CRUD + 手动预热 + 订单查询 + 库存查询
 * <p>
 * Gateway 对 /seckill/** 路径启用滑动窗口限流（每用户 5 秒 1 次）。
 */
@Api(tags = "秒杀接口")
@RestController
@RequestMapping("/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;

    // ==================== C 端接口 ====================

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

    // ==================== 管理后台 - 活动管理 ====================

    @ApiOperation("管理后台-分页查询秒杀活动")
    @GetMapping("/admin/promotion/page")
    public PageDTO<SeckillPromotionAdminVO> queryPromotionPage(PageQuery pageQuery,
                                                                 @RequestParam(required = false) String title,
                                                                 @RequestParam(required = false) Integer status) {
        return seckillService.queryPromotionPage(pageQuery, title, status);
    }

    @ApiOperation("管理后台-查询秒杀活动详情")
    @GetMapping("/admin/promotion/{id}")
    public SeckillPromotionAdminVO getPromotionDetail(@PathVariable Long id) {
        return seckillService.getPromotionDetail(id);
    }

    @ApiOperation("管理后台-创建秒杀活动")
    @PostMapping("/admin/promotion")
    public Long createPromotion(@RequestBody @Valid SeckillPromotionDTO dto) {
        return seckillService.createPromotion(dto);
    }

    @ApiOperation("管理后台-修改秒杀活动")
    @PutMapping("/admin/promotion")
    public void updatePromotion(@RequestBody @Valid SeckillPromotionDTO dto) {
        seckillService.updatePromotion(dto);
    }

    @ApiOperation("管理后台-删除秒杀活动")
    @DeleteMapping("/admin/promotion/{id}")
    public void deletePromotion(@PathVariable Long id) {
        seckillService.deletePromotion(id);
    }

    // ==================== 管理后台 - 场次管理 ====================

    @ApiOperation("管理后台-分页查询秒杀场次")
    @GetMapping("/admin/session/page")
    public PageDTO<SeckillSessionAdminVO> querySessionPage(PageQuery pageQuery,
                                                             @RequestParam(required = false) Long promotionId) {
        return seckillService.querySessionPage(pageQuery, promotionId);
    }

    @ApiOperation("管理后台-查询秒杀场次详情")
    @GetMapping("/admin/session/{id}")
    public SeckillSessionAdminVO getSessionDetail(@PathVariable Long id) {
        return seckillService.getSessionDetail(id);
    }

    @ApiOperation("管理后台-创建秒杀场次")
    @PostMapping("/admin/session")
    public Long createSession(@RequestBody @Valid SeckillSessionDTO dto) {
        return seckillService.createSession(dto);
    }

    @ApiOperation("管理后台-修改秒杀场次")
    @PutMapping("/admin/session")
    public void updateSession(@RequestBody @Valid SeckillSessionDTO dto) {
        seckillService.updateSession(dto);
    }

    @ApiOperation("管理后台-删除秒杀场次")
    @DeleteMapping("/admin/session/{id}")
    public void deleteSession(@PathVariable Long id) {
        seckillService.deleteSession(id);
    }

    // ==================== 管理后台 - 商品关联管理 ====================

    @ApiOperation("管理后台-分页查询秒杀商品关联")
    @GetMapping("/admin/relation/page")
    public PageDTO<SeckillProductRelationAdminVO> queryRelationPage(PageQuery pageQuery,
                                                                       @RequestParam(required = false) Long sessionId,
                                                                       @RequestParam(required = false) Long promotionId) {
        return seckillService.queryRelationPage(pageQuery, sessionId, promotionId);
    }

    @ApiOperation("管理后台-查询秒杀商品关联详情")
    @GetMapping("/admin/relation/{id}")
    public SeckillProductRelationAdminVO getRelationDetail(@PathVariable Long id) {
        return seckillService.getRelationDetail(id);
    }

    @ApiOperation("管理后台-创建秒杀商品关联")
    @PostMapping("/admin/relation")
    public Long createRelation(@RequestBody @Valid SeckillProductRelationDTO dto) {
        return seckillService.createRelation(dto);
    }

    @ApiOperation("管理后台-修改秒杀商品关联")
    @PutMapping("/admin/relation")
    public void updateRelation(@RequestBody @Valid SeckillProductRelationDTO dto) {
        seckillService.updateRelation(dto);
    }

    @ApiOperation("管理后台-删除秒杀商品关联")
    @DeleteMapping("/admin/relation/{id}")
    public void deleteRelation(@PathVariable Long id) {
        seckillService.deleteRelation(id);
    }

    @ApiOperation("管理后台-手动预热秒杀库存")
    @PostMapping("/admin/relation/preheat/{id}")
    public void manualPreheat(@PathVariable Long id) {
        seckillService.manualPreheat(id);
    }

    // ==================== 管理后台 - 秒杀订单管理 ====================

    @ApiOperation("管理后台-分页查询秒杀订单")
    @GetMapping("/admin/order/page")
    public PageDTO<SeckillOrderAdminVO> querySeckillOrderPage(PageQuery pageQuery,
                                                                 @RequestParam(required = false) Integer status,
                                                                 @RequestParam(required = false) Long relationId,
                                                                 @RequestParam(required = false) Long userId) {
        return seckillService.querySeckillOrderPage(pageQuery, status, relationId, userId);
    }

    // ==================== 管理后台 - 库存查询 ====================

    @ApiOperation("管理后台-查询秒杀商品库存状态")
    @GetMapping("/admin/stock/{relationId}")
    public List<SeckillStockAdminVO> queryStockStatus(@PathVariable Long relationId) {
        return seckillService.queryStockStatus(relationId);
    }
}
