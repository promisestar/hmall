package com.hmall.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmall.admin.domain.dto.AdminUserDTO;
import com.hmall.admin.domain.po.AdminUser;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;

import java.util.List;

/**
 * 管理员服务
 */
public interface IAdminUserService extends IService<AdminUser> {

    /**
     * 分页查询管理员
     */
    PageDTO<AdminUserDTO> queryAdminPage(PageQuery pageQuery, String keyword);

    /**
     * 查询管理员详情（含角色 ID）
     */
    AdminUserDTO queryAdminById(Long id);

    /**
     * 新增管理员
     */
    void createAdmin(AdminUserDTO adminUserDTO);

    /**
     * 更新管理员信息
     */
    void updateAdmin(Long id, AdminUserDTO adminUserDTO);

    /**
     * 删除管理员
     */
    void deleteAdmin(Long id);

    /**
     * 修改密码
     */
    void updatePassword(String oldPassword, String newPassword);

    /**
     * 修改启用状态
     */
    void updateStatus(Long id, Integer status);

    /**
     * 给管理员分配角色
     */
    void allocRoles(Long adminId, List<Long> roleIds);

    /**
     * 获取管理员的角色 ID 列表
     */
    List<Long> getRoleIdsByAdminId(Long adminId);
}
