package com.itheima.mp.domain.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * ClassName: UserQuery
 * Package: com.itheima.mp.domain.query
 * Description:
 *
 * @Author Raiden
 * @Create 2025/10/27 15:59
 * @Version 1.0
 */
@Data
@Schema(description = "用户查询条件实体")
public class UserQuery extends PageQuery{
    @Schema(description = "用户名关键字")
    private String name;
    @Schema(description = "用户状态：1-正常，2-冻结")
    private Integer status;
    @Schema(description = "余额最小值")
    private Integer minBalance;
    @Schema(description = "余额最大值")
    private Integer maxBalance;
}
