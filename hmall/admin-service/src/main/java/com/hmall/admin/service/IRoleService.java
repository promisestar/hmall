package com.hmall.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmall.admin.domain.po.Role;

import java.util.List;

/**
 * 角色服务
 */
public interface IRoleService extends IService<Role> {

    /**
     * 给管理员分配角色（更新关联表，同时更新 role.admin_count）
     */
    void allocRoles(Long adminId, List<Long> roleIds);

    /**
     * 获取管理员的角色列表
     */
    List<Role> getRolesByAdminId(Long adminId);

    /**
     * 给角色分配菜单
     */
    void allocMenus(Long roleId, List<Long> menuIds);

    /**
     * 给角色分配资源
     */
    void allocResources(Long roleId, List<Long> resourceIds);

    /**
     * 获取角色的菜单 ID 列表
     */
    List<Long> getMenuIdsByRoleId(Long roleId);

    /**
     * 获取角色的资源 ID 列表
     */
    List<Long> getResourceIdsByRoleId(Long roleId);
}
