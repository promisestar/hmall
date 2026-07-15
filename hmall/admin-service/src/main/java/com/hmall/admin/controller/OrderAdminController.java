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

@Api(tags = "订单管理接口")
@RestController
@RequestMapping("/admin/order")
@RequiredArgsConstructor
public class OrderAdminController {

    private final TradeFeignClient tradeFeignClient;

    @ApiOperation("分页查询订单")
    @GetMapping("/list")
    public R<PageDTO<Object>> list(PageQuery pageQuery,
                                    @RequestParam(required = false) Integer status,
                                    @RequestParam(required = false) Long orderId,
                                    @RequestParam(required = false) String startTime,
                                    @RequestParam(required = false) String endTime) {
        return R.ok(tradeFeignClient.queryOrderByPage(pageQuery, status, orderId, startTime, endTime));
    }

    @ApiOperation("订单详情")
    @GetMapping("/{id}")
    public R<Object> getInfo(@PathVariable Long id) {
        return tradeFeignClient.queryOrderById(id);
    }

    @ApiOperation("批量发货")
    @PostMapping("/delivery")
    public R<Void> batchDelivery(@RequestBody List<Long> orderIds) {
        tradeFeignClient.batchDelivery(orderIds);
        return R.ok();
    }

    @ApiOperation("批量关闭订单")
    @PostMapping("/close")
    public R<Void> batchClose(@RequestBody List<Long> orderIds) {
        tradeFeignClient.batchCloseOrders(orderIds);
        return R.ok();
    }

    @ApiOperation("修改备注")
    @PostMapping("/note")
    public R<Void> updateNote(@RequestParam Long id,
                              @RequestParam(required = false) String note,
                              @RequestParam(required = false) Integer status) {
        tradeFeignClient.updateNote(id, note, status);
        return R.ok();
    }
}
