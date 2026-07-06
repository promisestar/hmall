package com.hmall.search.domain.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * ClassName: CategoryBrandVO
 * Package: com.hmall.search.domain.vo
 * Description:
 *
 * @Author Raiden
 * @Create 2026/1/12 14:24
 * @Version 1.0
 */
@Data
@ApiModel(description = "类别品牌过滤")
public class CategoryBrandVO {

    @ApiModelProperty("返回列表")
    private HashMap<String, List<String>> resultMap = new HashMap<>();

//    @ApiModelProperty("商品分类列表")
//    private List<String> categoryList;
//
//    @ApiModelProperty("商品品牌列表")
//    private List<String> brandList;

    public CategoryBrandVO() {
        this.resultMap.put("brand", new ArrayList<>());
        this.resultMap.put("category", new ArrayList<>());
    }
}
