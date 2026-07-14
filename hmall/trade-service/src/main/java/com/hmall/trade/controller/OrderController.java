package com.hmall.trade.controller;

import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.utils.BeanUtils;

import com.hmall.trade.domain.dto.OrderFormDTO;
import com.hmall.trade.domain.po.Order;
import com.hmall.trade.domain.vo.OrderVO;
import com.hmall.trade.service.IOrderService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;

@Api(tags = "订单管理接口")
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {
    private final IOrderService orderService;

    @ApiOperation("根据id查询订单")
    @GetMapping("{id}")
    public OrderVO queryOrderById(@Param ("订单id")@PathVariable("id") Long orderId) {
        return BeanUtils.copyBean(orderService.getById(orderId), OrderVO.class);
    }

    @ApiOperation("创建订单")
    @PostMapping
    public Long createOrder(@RequestBody @Valid OrderFormDTO orderFormDTO){
        return orderService.createOrder(orderFormDTO);
    }

    @ApiOperation("标记订单已支付")
    @ApiImplicitParam(name = "orderId", value = "订单id", paramType = "path")
    @PutMapping("/{orderId}")
    public void markOrderPaySuccess(@PathVariable("orderId") Long orderId) {
        orderService.markOrderPaySuccess(orderId);
    }

    @ApiOperation("分页查询当前用户订单")
    @GetMapping("/page")
    public PageDTO<OrderVO> queryOrderPage(PageQuery pageQuery) {
        return orderService.queryOrderPage(pageQuery);
    }

    // ==================== 管理后台接口（admin-service 调用） ====================

    @ApiOperation("管理后台分页查询订单")
    @GetMapping("/admin/page")
    public PageDTO<OrderVO> queryOrderAdminPage(PageQuery pageQuery,
                                                  @RequestParam(required = false) Integer status,
                                                  @RequestParam(required = false) Long orderId,
                                                  @RequestParam(required = false) String startTime,
                                                  @RequestParam(required = false) String endTime) {
        return orderService.queryOrderAdminPage(pageQuery, status, orderId, startTime, endTime);
    }

    @ApiOperation("批量发货")
    @PostMapping("/batch/delivery")
    public void batchDelivery(@RequestBody List<Long> orderIds) {
        for (Long orderId : orderIds) {
            Order order = new Order();
            order.setId(orderId);
            order.setStatus(3); // 已发货
            order.setConsignTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());
            orderService.updateById(order);
        }
    }

    @ApiOperation("批量关闭订单")
    @PostMapping("/batch/close")
    public void batchCloseOrders(@RequestBody List<Long> orderIds) {
        for (Long orderId : orderIds) {
            Order order = new Order();
            order.setId(orderId);
            order.setStatus(5); // 交易取消，订单关闭
            order.setCloseTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());
            orderService.updateById(order);
        }
    }

    @ApiOperation("修改订单备注和状态")
    @PostMapping("/{id}/note")
    public void updateNote(@PathVariable Long id, @RequestParam(required = false) String note,
                           @RequestParam(required = false) Integer status) {
        // hmall 的 Order 没有 note 字段，此处仅更新状态
        Order order = new Order();
        order.setId(id);
        if (status != null) {
            order.setStatus(status);
        }
        order.setUpdateTime(LocalDateTime.now());
        orderService.updateById(order);
    }
}
