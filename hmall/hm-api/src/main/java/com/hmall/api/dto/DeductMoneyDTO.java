package com.hmall.api.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
@ApiModel(description = "扣减余额表单实体")
public class DeductMoneyDTO {
    @ApiModelProperty("支付密码")
    @NotBlank(message = "支付密码不能为空")
    private String pw;
    @ApiModelProperty("支付金额")
    @NotNull(message = "支付金额不能为空")
    @Min(value = 1, message = "支付金额必须为正数")
    private Integer amount;
}
