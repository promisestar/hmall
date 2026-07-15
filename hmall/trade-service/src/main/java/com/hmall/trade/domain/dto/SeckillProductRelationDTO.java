package com.hmall.trade.domain.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 秒杀商品关联创建/修改 DTO
 */
@Data
@Accessors(chain = true)
public class SeckillProductRelationDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * ID（修改时有值）
     */
    private Long id;

    @NotNull(message = "活动ID不能为空")
    private Long promotionId;

    @NotNull(message = "场次ID不能为空")
    private Long sessionId;

    @NotNull(message = "商品ID不能为空")
    private Long productId;

    @NotNull(message = "秒杀价不能为空")
    @Min(value = 1, message = "秒杀价必须大于0")
    private Integer seckillPrice;

    @NotNull(message = "库存不能为空")
    @Min(value = 1, message = "库存必须大于0")
    private Integer stock;

    @NotNull(message = "限购数量不能为空")
    @Min(value = 1, message = "限购数量必须大于0")
    private Integer limitNum;
}
