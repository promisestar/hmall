package com.hmall.item.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/**
 * 推荐响应 VO
 */
@Data
@ApiModel(description = "推荐响应")
public class RecommendVO {

    @ApiModelProperty("推荐商品列表")
    private List<RecommendItemDTO> list;

    @ApiModelProperty("总数")
    private Integer total;

    @ApiModelProperty("推荐依据")
    private BasedOn basedOn;

    @Data
    @ApiModel(description = "推荐依据摘要")
    public static class BasedOn {

        @ApiModelProperty("偏好类目 Top3")
        private List<String> topCategories;

        @ApiModelProperty("偏好品牌 Top3")
        private List<String> topBrands;
    }
}
