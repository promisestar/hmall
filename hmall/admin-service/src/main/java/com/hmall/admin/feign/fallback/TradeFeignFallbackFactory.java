package com.hmall.admin.feign.fallback;

import com.hmall.admin.feign.TradeFeignClient;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class TradeFeignFallbackFactory implements FallbackFactory<TradeFeignClient> {
    @Override
    public TradeFeignClient create(Throwable cause) {
        log.error("调用 trade-service 失败", cause);
        return new TradeFeignClient() {
            @Override
            public PageDTO<Object> queryOrderByPage(PageQuery pageQuery, Integer status, Long orderId,
                                                      String startTime, String endTime) {
                return new PageDTO<>();
            }

            @Override
            public R<Object> queryOrderById(Long id) {
                return R.error("订单服务暂时不可用");
            }

            @Override
            public void batchDelivery(List<Long> orderIds) {
            }

            @Override
            public void batchCloseOrders(List<Long> orderIds) {
            }

            @Override
            public void updateNote(Long id, String note, Integer status) {
            }

            // ==================== 秒杀管理 Fallback ====================

            @Override
            public PageDTO<Object> queryPromotionPage(PageQuery pageQuery, String title, Integer status) {
                return new PageDTO<>();
            }

            @Override
            public Object getPromotionDetail(Long id) {
                return null;
            }

            @Override
            public Long createPromotion(Object dto) {
                return null;
            }

            @Override
            public void updatePromotion(Object dto) {
            }

            @Override
            public void deletePromotion(Long id) {
            }

            @Override
            public PageDTO<Object> querySessionPage(PageQuery pageQuery, Long promotionId) {
                return new PageDTO<>();
            }

            @Override
            public Object getSessionDetail(Long id) {
                return null;
            }

            @Override
            public Long createSession(Object dto) {
                return null;
            }

            @Override
            public void updateSession(Object dto) {
            }

            @Override
            public void deleteSession(Long id) {
            }

            @Override
            public PageDTO<Object> queryRelationPage(PageQuery pageQuery, Long sessionId, Long promotionId) {
                return new PageDTO<>();
            }

            @Override
            public Object getRelationDetail(Long id) {
                return null;
            }

            @Override
            public Long createRelation(Object dto) {
                return null;
            }

            @Override
            public void updateRelation(Object dto) {
            }

            @Override
            public void deleteRelation(Long id) {
            }

            @Override
            public void manualPreheat(Long id) {
            }

            @Override
            public PageDTO<Object> querySeckillOrderPage(PageQuery pageQuery, Integer status, Long relationId, Long userId) {
                return new PageDTO<>();
            }

            @Override
            public List<Object> queryStockStatus(Long relationId) {
                return java.util.Collections.emptyList();
            }
        };
    }
}
