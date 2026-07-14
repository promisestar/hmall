package com.hmall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmall.admin.domain.po.RoleResourceRel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RoleResourceRelMapper extends BaseMapper<RoleResourceRel> {

    @Select("SELECT resource_id FROM role_resource_rel WHERE role_id = #{roleId}")
    List<Long> selectResourceIdsByRoleId(Long roleId);
}
