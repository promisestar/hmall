package com.hmall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmall.admin.domain.po.RoleMenuRel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RoleMenuRelMapper extends BaseMapper<RoleMenuRel> {

    @Select("SELECT menu_id FROM role_menu_rel WHERE role_id = #{roleId}")
    List<Long> selectMenuIdsByRoleId(Long roleId);
}
