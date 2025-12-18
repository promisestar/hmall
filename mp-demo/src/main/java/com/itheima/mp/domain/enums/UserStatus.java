package com.itheima.mp.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * ClassName: UserStatus
 * Package: com.itheima.mp.domain.enums
 * Description:
 *
 * @Author Raiden
 * @Create 2025/10/29 20:10
 * @Version 1.0
 */
public enum UserStatus {
    NORMAL(1, "正常"),
    FROZEN(2, "冻结"),
    ;
    @EnumValue
    private Integer value;
    @JsonValue
    private String desc;
    UserStatus(Integer value, String desc) {
        this.value = value;
        this.desc = desc;
    }
}
