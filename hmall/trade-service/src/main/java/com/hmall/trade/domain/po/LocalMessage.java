package com.hmall.trade.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hmall.trade.utils.LongListJsonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * ClassName: LocalMessage
 * Package: com.hmall.trade.domain.po
 * Description:
 *
 * @Author Raiden
 * @Create 2025/12/31 15:54
 * @Version 1.0
 */
@Data
@TableName(value = "t_local_message", autoResultMap = true)
public class LocalMessage {
    private Long id;
    private String messageId;      // 通常用 bizOrderNo + suffix
    private String exchange;
    private String routingKey;
    @TableField(typeHandler = LongListJsonTypeHandler.class)
    private List<Long> messageBody;    // JSON or raw string
    private Integer status;        // 0: pending, 1: success, 2: failed
    private Integer tryCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    // 新增：存储消息产生时的用户ID
    private Long userId;
}