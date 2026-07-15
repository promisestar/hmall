package com.hmall.trade.domain.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 秒杀活动创建/修改 DTO
 */
@Data
@Accessors(chain = true)
public class SeckillPromotionDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * ID（修改时有值）
     */
    private Long id;

    @NotBlank(message = "活动标题不能为空")
    private String title;

    @NotNull(message = "开始日期不能为空")
    private LocalDate startDate;

    @NotNull(message = "结束日期不能为空")
    private LocalDate endDate;
}
