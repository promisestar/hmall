package com.itheima.mp.domain.dto;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ClassName: PageDTO
 * Package: com.itheima.mp.domain.dto
 * Description:
 *
 * @Author Raiden
 * @Create 2025/10/30 20:26
 * @Version 1.0
 */

@Data
@Schema(description = "分页查询DTO")
public class PageDTO<T>{
    @Schema(description = "总记录数")
    private Long total;
    @Schema(description = "总页数")
    private Long pages;
    @Schema(description = "当前页数据")
    private List<T> list;

    public static <PO, VO> PageDTO<VO> of(Page<PO> page, Class<VO> clazz){
        PageDTO<VO> dto = new PageDTO<>();
        dto.setPages(page.getPages());
        dto.setTotal(page.getTotal());
        List<PO> records = page.getRecords();
        if(CollUtil.isEmpty(records)){
            dto.setList(Collections.emptyList());
            return dto;
        }
        dto.setList(BeanUtil.copyToList(records, clazz));
        return dto;
    }

    public static <PO, VO> PageDTO<VO> of(Page<PO> page, Function<PO, VO> convertor){
        PageDTO<VO> dto = new PageDTO<>();
        dto.setPages(page.getPages());
        dto.setTotal(page.getTotal());
        List<PO> records = page.getRecords();
        if(CollUtil.isEmpty(records)) {
            dto.setList(Collections.emptyList());
            return dto;
        }
        dto.setList(records.stream().map(convertor).toList());
        return dto;
    }
}
