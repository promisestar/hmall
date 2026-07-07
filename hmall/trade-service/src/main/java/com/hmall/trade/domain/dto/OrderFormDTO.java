package com.hmall.trade.domain.dto;

import com.hmall.api.dto.OrderDetailDTO;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
@ApiModel(description = "交易下单表单实体")
public class OrderFormDTO {
    @ApiModelProperty("收货地址id")
    private Long addressId;
    @ApiModelProperty("支付类型")
    @NotNull(message = "支付类型不能为空")
    private Integer paymentType;
    @ApiModelProperty("下单商品列表")
    @NotEmpty(message = "下单商品列表不能为空")
    private List<OrderDetailDTO> details;
}
