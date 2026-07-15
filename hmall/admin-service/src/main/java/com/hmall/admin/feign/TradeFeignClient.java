package com.hmall.admin.feign;

import com.hmall.admin.feign.fallback.TradeFeignFallbackFactory;
import com.hmall.api.config.DefaultFeignConfig;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(name = "trade-service", contextId = "admin-trade",
        configuration = DefaultFeignConfig.class,
        fallbackFactory = TradeFeignFallbackFactory.class)
public interface TradeFeignClient {

    @GetMapping("/orders/admin/page")
    PageDTO<Object> queryOrderByPage(@SpringQueryMap PageQuery pageQuery,
                                      @RequestParam(required = false) Integer status,
                                      @RequestParam(required = false) Long orderId,
                                      @RequestParam(required = false) String startTime,
                                      @RequestParam(required = false) String endTime);

    @GetMapping("/orders/{id}")
    R<Object> queryOrderById(@PathVariable Long id);

    @PostMapping("/orders/batch/delivery")
    R<Void> batchDelivery(@RequestBody List<Long> orderIds);

    @PostMapping("/orders/batch/close")
    R<Void> batchCloseOrders(@RequestBody List<Long> orderIds);

    @PostMapping("/orders/{id}/note")
    R<Void> updateNote(@PathVariable Long id, @RequestParam(required = false) String note,
                       @RequestParam(required = false) Integer status);

    // ==================== 秒杀管理 ====================

    // 活动管理
    @GetMapping("/seckill/admin/promotion/page")
    PageDTO<Object> queryPromotionPage(@SpringQueryMap PageQuery pageQuery,
                                        @RequestParam(required = false) String title,
                                        @RequestParam(required = false) Integer status);

    @GetMapping("/seckill/admin/promotion/{id}")
    Object getPromotionDetail(@PathVariable Long id);

    @PostMapping("/seckill/admin/promotion")
    Long createPromotion(@RequestBody Object dto);

    @PutMapping("/seckill/admin/promotion")
    void updatePromotion(@RequestBody Object dto);

    @DeleteMapping("/seckill/admin/promotion/{id}")
    void deletePromotion(@PathVariable Long id);

    // 场次管理
    @GetMapping("/seckill/admin/session/page")
    PageDTO<Object> querySessionPage(@SpringQueryMap PageQuery pageQuery,
                                      @RequestParam(required = false) Long promotionId);

    @GetMapping("/seckill/admin/session/{id}")
    Object getSessionDetail(@PathVariable Long id);

    @PostMapping("/seckill/admin/session")
    Long createSession(@RequestBody Object dto);

    @PutMapping("/seckill/admin/session")
    void updateSession(@RequestBody Object dto);

    @DeleteMapping("/seckill/admin/session/{id}")
    void deleteSession(@PathVariable Long id);

    // 商品关联管理
    @GetMapping("/seckill/admin/relation/page")
    PageDTO<Object> queryRelationPage(@SpringQueryMap PageQuery pageQuery,
                                       @RequestParam(required = false) Long sessionId,
                                       @RequestParam(required = false) Long promotionId);

    @GetMapping("/seckill/admin/relation/{id}")
    Object getRelationDetail(@PathVariable Long id);

    @PostMapping("/seckill/admin/relation")
    Long createRelation(@RequestBody Object dto);

    @PutMapping("/seckill/admin/relation")
    void updateRelation(@RequestBody Object dto);

    @DeleteMapping("/seckill/admin/relation/{id}")
    void deleteRelation(@PathVariable Long id);

    @PostMapping("/seckill/admin/relation/preheat/{id}")
    void manualPreheat(@PathVariable Long id);

    // 秒杀订单管理
    @GetMapping("/seckill/admin/order/page")
    PageDTO<Object> querySeckillOrderPage(@SpringQueryMap PageQuery pageQuery,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(required = false) Long relationId,
                                           @RequestParam(required = false) Long userId);

    // 库存查询
    @GetMapping("/seckill/admin/stock/{relationId}")
    List<Object> queryStockStatus(@PathVariable Long relationId);
}
