package com.hmall.api.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
@ApiModel(description = "商品实体")
public class ItemDTO {
    @ApiModelProperty("商品id")
    private Long id;
    @ApiModelProperty("SKU名称")
    @NotBlank(message = "商品名称不能为空")
    private String name;
    @ApiModelProperty("价格（分）")
    @NotNull(message = "价格不能为空")
    @Min(value = 1, message = "价格必须为正数")
    private Integer price;
    @ApiModelProperty("库存数量")
    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;
    @ApiModelProperty("商品图片")
    private String image;
    @ApiModelProperty("类目名称")
    private String category;
    @ApiModelProperty("品牌名称")
    private String brand;
    @ApiModelProperty("规格")
    private String spec;
    @ApiModelProperty("销量")
    private Integer sold;
    @ApiModelProperty("评论数")
    private Integer commentCount;
    @ApiModelProperty("是否是推广广告，true/false")
    private Boolean isAD;
    @ApiModelProperty("商品状态 1-正常，2-下架，3-删除")
    private Integer status;
}
