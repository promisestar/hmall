package com.hmall.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.admin.domain.po.*;
import com.hmall.admin.mapper.*;
import com.hmall.admin.service.IRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl extends ServiceImpl<RoleMapper, Role> implements IRoleService {

    private final AdminUserRoleRelMapper adminUserRoleRelMapper;
    private final RoleMenuRelMapper roleMenuRelMapper;
    private final RoleResourceRelMapper roleResourceRelMapper;

    @Override
    @Transactional
    public void allocRoles(Long adminId, List<Long> roleIds) {
        // 删除旧关联
        adminUserRoleRelMapper.delete(new LambdaQueryWrapper<AdminUserRoleRel>()
                .eq(AdminUserRoleRel::getAdminUserId, adminId));
        // 新增新关联
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                AdminUserRoleRel rel = new AdminUserRoleRel();
                rel.setAdminUserId(adminId);
                rel.setRoleId(roleId);
                adminUserRoleRelMapper.insert(rel);
            }
            // 更新 role.admin_count
            for (Long roleId : roleIds) {
                Role role = getById(roleId);
                if (role != null) {
                    long count = adminUserRoleRelMapper.selectCount(new LambdaQueryWrapper<AdminUserRoleRel>()
                            .eq(AdminUserRoleRel::getRoleId, roleId));
                    role.setAdminCount((int) count);
                    updateById(role);
                }
            }
        }
    }

    @Override
    public List<Role> getRolesByAdminId(Long adminId) {
        List<Long> roleIds = adminUserRoleRelMapper.selectRoleIdsByAdminUserId(adminId);
        if (roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        return listByIds(roleIds);
    }

    @Override
    @Transactional
    public void allocMenus(Long roleId, List<Long> menuIds) {
        roleMenuRelMapper.delete(new LambdaQueryWrapper<RoleMenuRel>()
                .eq(RoleMenuRel::getRoleId, roleId));
        if (menuIds != null) {
            for (Long menuId : menuIds) {
                RoleMenuRel rel = new RoleMenuRel();
                rel.setRoleId(roleId);
                rel.setMenuId(menuId);
                roleMenuRelMapper.insert(rel);
            }
        }
    }

    @Override
    @Transactional
    public void allocResources(Long roleId, List<Long> resourceIds) {
        roleResourceRelMapper.delete(new LambdaQueryWrapper<RoleResourceRel>()
                .eq(RoleResourceRel::getRoleId, roleId));
        if (resourceIds != null) {
            for (Long resourceId : resourceIds) {
                RoleResourceRel rel = new RoleResourceRel();
                rel.setRoleId(roleId);
                rel.setResourceId(resourceId);
                roleResourceRelMapper.insert(rel);
            }
        }
    }

    @Override
    public List<Long> getMenuIdsByRoleId(Long roleId) {
        return roleMenuRelMapper.selectMenuIdsByRoleId(roleId);
    }

    @Override
    public List<Long> getResourceIdsByRoleId(Long roleId) {
        return roleResourceRelMapper.selectResourceIdsByRoleId(roleId);
    }
}
