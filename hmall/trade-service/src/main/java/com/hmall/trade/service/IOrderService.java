package com.hmall.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmall.api.dto.OrderDetailDTO;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.trade.domain.dto.OrderFormDTO;
import com.hmall.trade.domain.po.Order;
import com.hmall.trade.domain.vo.OrderVO;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
public interface IOrderService extends IService<Order> {

    Long createOrder(OrderFormDTO orderFormDTO);

    void markOrderPaySuccess(Long orderId);

    void cancelOrder(Long orderId);

    /**
     * 分页查询当前用户的订单
     * @param pageQuery 分页参数
     * @return 订单分页结果
     */
    PageDTO<OrderVO> queryOrderPage(PageQuery pageQuery);

    /**
     * 管理后台分页查询订单（支持多条件筛选）
     */
    PageDTO<OrderVO> queryOrderAdminPage(PageQuery pageQuery, Integer status, Long orderId,
                                          String startTime, String endTime);

    /**
     * 获取当前用户已购商品 ID 及数量（有效订单聚合，推荐服务调用）
     *
     * @return 按 itemId 聚合购买数量后的列表，无购买记录时返回空列表
     */
    List<OrderDetailDTO> queryPurchasedItems();
}
