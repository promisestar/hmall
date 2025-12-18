package com.itheima.mp.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * <p>
 * 
 * </p>
 *
 * @author 虎哥
 * @since 2023-07-01
 */
@Data
@Schema(description = "收货地址VO")
public class AddressVO{

    @Schema(description = "id")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "省")
    private String province;

    @Schema(description = "市")
    private String city;

    @Schema(description = "县/区")
    private String town;

    @Schema(description = "手机")
    private String mobile;

    @Schema(description = "详细地址")
    private String street;

    @Schema(description = "联系人")
    private String contact;

    @Schema(description = "是否是默认 1默认 0否")
    private Boolean isDefault;

    @Schema(description = "备注")
    private String notes;
}
