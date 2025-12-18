package com.itheima.mp.domain.query;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * ClassName: PageQuery
 * Package: com.itheima.mp.domain.query
 * Description:
 *
 * @Author Raiden
 * @Create 2025/10/30 20:22
 * @Version 1.0
 */
@Data
@Schema(description = "分页查询实体")
public class PageQuery<T> {
    @Schema(description = "当前页码")
    private Long pageNo;
    @Schema(description = "每页数量")
    private Long pageSize;
    @Schema(description = "排序字段")
    private String sortBy;
    @Schema(description = "是否升序")
    private boolean isAsc;

    // 实现将PageQuery转换为Page对象
    public <T> Page<T> toMpPage(OrderItem... items) {
        Page<T> page = Page.of(pageNo, pageSize);
        if(StrUtil.isNotBlank((sortBy))){
            page.addOrder(isAsc ? OrderItem.asc(sortBy) : OrderItem.desc(sortBy));
        }else if(items != null){
            page.addOrder(items);
        }
        return page;
    }

    // 提供默认排序方式
    public <T> Page<T> toMpPageDefaultSortByUpdateTime() {
       return toMpPage(OrderItem.desc("update_time"));
    }
    public <T> Page<T> toMpPageDefaultSortByCreateTime() {
        return toMpPage(OrderItem.desc("create_time"));
    }
    public <T> Page<T> toMpPage(String defaultSortBy, Boolean defaultIsAsc) {
        return toMpPage(defaultIsAsc ? OrderItem.asc(defaultSortBy) : OrderItem.desc(defaultSortBy));
    }
}
