package com.hmall.pay.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ClassName: LocalMessage
 * Package: com.hmall.pay.domain.po
 * Description:
 *
 * @Author Raiden
 * @Create 2025/12/30 11:19
 * @Version 1.0
 */
@Data
@TableName("t_local_message")
public class LocalMessage {
    private Long id;
    private String messageId;      // 通常用 bizOrderNo + suffix
    private String exchange;
    private String routingKey;
    private Long messageBody;    // JSON or raw string
    private Integer status;        // 0: pending, 1: success, 2: failed
    private Integer tryCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}