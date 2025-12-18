package com.itheima.mp.domain.dto;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户表单实体")
public class UserFormDTO {

    @Schema(description = "id")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "密码")
    private String password;

    @Schema(description = "注册手机号")
    private String phone;

    @Schema(description = "详细信息，JSON风格")
    private String info;

    @Schema(description = "账户余额")
    private Integer balance;
}
