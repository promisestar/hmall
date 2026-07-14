package com.hmall.trade.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀活动列表 VO（含场次、商品列表、倒计时状态）
 */
@Data
@Accessors(chain = true)
public class SeckillActivityVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    /**
     * 活动状态: 0未开始 1进行中 2已结束
     */
    private Integer status;

    /**
     * 场次列表
     */
    private List<SessionVO> sessions;

    @Data
    @Accessors(chain = true)
    public static class SessionVO implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long id;

        private String name;

        private LocalDateTime startTime;

        private LocalDateTime endTime;

        /**
         * 场次状态: 0未开始 1进行中 2已结束
         */
        private Integer status;

        /**
         * 该场次的秒杀商品列表
         */
        private List<SeckillProductVO> products;
    }
}
