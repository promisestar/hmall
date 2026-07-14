package com.hmall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmall.admin.domain.po.AdminUserRoleRel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AdminUserRoleRelMapper extends BaseMapper<AdminUserRoleRel> {

    @Select("SELECT role_id FROM admin_user_role_rel WHERE admin_user_id = #{adminUserId}")
    List<Long> selectRoleIdsByAdminUserId(Long adminUserId);
}
