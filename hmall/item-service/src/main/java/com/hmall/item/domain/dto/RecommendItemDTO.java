package com.hmall.item.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/**
 * 推荐商品 DTO
 */
@Data
@ApiModel(description = "推荐商品")
public class RecommendItemDTO {

    @ApiModelProperty("商品id")
    private Long id;

    @ApiModelProperty("SKU名称")
    private String name;

    @ApiModelProperty("价格（分）")
    private Integer price;

    @ApiModelProperty("库存数量")
    private Integer stock;

    @ApiModelProperty("品牌名称")
    private String brand;

    @ApiModelProperty("类目名称")
    private String category;

    @ApiModelProperty("销量")
    private Integer sold;

    @ApiModelProperty("推荐标签")
    private List<String> recommendTags;
}
