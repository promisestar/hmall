package com.hmall.trade.domain.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀场次创建/修改 DTO
 */
@Data
@Accessors(chain = true)
public class SeckillSessionDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * ID（修改时有值）
     */
    private Long id;

    @NotNull(message = "活动ID不能为空")
    private Long promotionId;

    @NotBlank(message = "场次名称不能为空")
    private String name;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;
}
