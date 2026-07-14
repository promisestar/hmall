package com.hmall.admin.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("role_resource_rel")
public class RoleResourceRel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roleId;
    private Long resourceId;
}
