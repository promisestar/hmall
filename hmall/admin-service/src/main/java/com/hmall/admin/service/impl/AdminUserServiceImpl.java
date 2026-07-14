package com.hmall.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.admin.domain.dto.AdminUserDTO;
import com.hmall.admin.domain.po.AdminUser;
import com.hmall.admin.mapper.AdminUserMapper;
import com.hmall.admin.service.IAdminUserService;
import com.hmall.admin.service.IRoleService;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.exception.BadRequestException;
import com.hmall.common.exception.CommonException;
import com.hmall.common.utils.BeanUtils;
import com.hmall.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl extends ServiceImpl<AdminUserMapper, AdminUser> implements IAdminUserService {

    private final IRoleService roleService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public PageDTO<AdminUserDTO> queryAdminPage(PageQuery pageQuery, String keyword) {
        LambdaQueryWrapper<AdminUser> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like(AdminUser::getUsername, keyword)
                    .or().like(AdminUser::getNickName, keyword);
        }
        wrapper.orderByDesc(AdminUser::getCreateTime);
        Page<AdminUser> page = page(pageQuery.toMpPage("create_time", false), wrapper);
        return PageDTO.of(page, AdminUserDTO.class);
    }

    @Override
    public AdminUserDTO queryAdminById(Long id) {
        AdminUser adminUser = getById(id);
        if (adminUser == null) {
            throw new BadRequestException("管理员不存在");
        }
        AdminUserDTO dto = AdminUserDTO.fromPO(adminUser);
        dto.setRoleIds(roleService.getRolesByAdminId(id).stream()
                .map(r -> r.getId()).toList());
        return dto;
    }

    @Override
    @Transactional
    public void createAdmin(AdminUserDTO adminUserDTO) {
        // 检查用户名是否已存在
        long count = count(new LambdaQueryWrapper<AdminUser>().eq(AdminUser::getUsername, adminUserDTO.getUsername()));
        if (count > 0) {
            throw new BadRequestException("用户名已存在");
        }
        AdminUser adminUser = new AdminUser();
        adminUser.setUsername(adminUserDTO.getUsername());
        adminUser.setPassword(passwordEncoder.encode(adminUserDTO.getPassword() != null ?
                adminUserDTO.getPassword() : "123456"));
        adminUser.setNickName(adminUserDTO.getNickName());
        adminUser.setEmail(adminUserDTO.getEmail());
        adminUser.setIcon(adminUserDTO.getIcon());
        adminUser.setNote(adminUserDTO.getNote());
        adminUser.setStatus(adminUserDTO.getStatus() != null ? adminUserDTO.getStatus() : 1);
        save(adminUser);
        // 分配角色
        if (adminUserDTO.getRoleIds() != null && !adminUserDTO.getRoleIds().isEmpty()) {
            roleService.allocRoles(adminUser.getId(), adminUserDTO.getRoleIds());
        }
    }

    @Override
    @Transactional
    public void updateAdmin(Long id, AdminUserDTO adminUserDTO) {
        AdminUser adminUser = getById(id);
        if (adminUser == null) {
            throw new BadRequestException("管理员不存在");
        }
        adminUser.setNickName(adminUserDTO.getNickName());
        adminUser.setEmail(adminUserDTO.getEmail());
        adminUser.setIcon(adminUserDTO.getIcon());
        adminUser.setNote(adminUserDTO.getNote());
        if (adminUserDTO.getStatus() != null) {
            adminUser.setStatus(adminUserDTO.getStatus());
        }
        // 如果传了密码，则更新密码
        if (adminUserDTO.getPassword() != null && !adminUserDTO.getPassword().isEmpty()) {
            adminUser.setPassword(passwordEncoder.encode(adminUserDTO.getPassword()));
        }
        updateById(adminUser);
    }

    @Override
    @Transactional
    public void deleteAdmin(Long id) {
        // 超级管理员不允许删除
        if (id == 1) {
            throw new BadRequestException("超级管理员不允许删除");
        }
        removeById(id);
        // 删除角色关联
        roleService.allocRoles(id, null);
    }

    @Override
    public void updatePassword(String oldPassword, String newPassword) {
        Long adminId = UserContext.getUser();
        AdminUser adminUser = getById(adminId);
        if (adminUser == null) {
            throw new CommonException(401, "管理员不存在");
        }
        if (!passwordEncoder.matches(oldPassword, adminUser.getPassword())) {
            throw new BadRequestException("旧密码不正确");
        }
        adminUser.setPassword(passwordEncoder.encode(newPassword));
        updateById(adminUser);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        AdminUser adminUser = new AdminUser();
        adminUser.setId(id);
        adminUser.setStatus(status);
        updateById(adminUser);
    }

    @Override
    @Transactional
    public void allocRoles(Long adminId, List<Long> roleIds) {
        roleService.allocRoles(adminId, roleIds);
    }

    @Override
    public List<Long> getRoleIdsByAdminId(Long adminId) {
        return roleService.getRolesByAdminId(adminId).stream()
                .map(r -> r.getId()).toList();
    }
}
