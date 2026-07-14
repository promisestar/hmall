package com.hmall.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmall.admin.domain.dto.AdminLoginDTO;
import com.hmall.admin.domain.po.AdminUser;
import com.hmall.admin.domain.vo.AdminInfoVO;
import com.hmall.admin.domain.vo.MenuNode;
import com.hmall.admin.domain.vo.TokenVO;
import com.hmall.admin.mapper.AdminUserMapper;
import com.hmall.admin.security.AdminJwtTool;
import com.hmall.admin.service.IAdminAuthService;
import com.hmall.admin.service.IMenuService;
import com.hmall.admin.service.IResourceService;
import com.hmall.admin.service.IRoleService;
import com.hmall.common.exception.BadRequestException;
import com.hmall.common.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthServiceImpl implements IAdminAuthService {

    private final AdminUserMapper adminUserMapper;
    private final AdminJwtTool adminJwtTool;
    private final PasswordEncoder passwordEncoder;
    private final IRoleService roleService;
    private final IMenuService menuService;
    private final IResourceService resourceService;
    private final RedisService redisService;

    private static final String BLACKLIST_KEY_PREFIX = "admin:blacklist:";

    @Override
    public TokenVO login(AdminLoginDTO loginDTO) {
        // 1. 查询管理员
        AdminUser adminUser = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, loginDTO.getUsername()));
        if (adminUser == null) {
            throw new BadRequestException("用户名或密码错误");
        }
        // 2. 检查状态
        if (adminUser.getStatus() != null && adminUser.getStatus() == 0) {
            throw new BadRequestException("账号已被禁用");
        }
        // 3. 校验密码
        if (!passwordEncoder.matches(loginDTO.getPassword(), adminUser.getPassword())) {
            throw new BadRequestException("用户名或密码错误");
        }
        // 4. 生成 token
        String token = adminJwtTool.createToken(adminUser.getId(), adminUser.getUsername());
        // 5. 更新登录时间
        AdminUser update = new AdminUser();
        update.setId(adminUser.getId());
        update.setLoginTime(LocalDateTime.now());
        adminUserMapper.updateById(update);
        log.info("管理员登录成功: {}", adminUser.getUsername());
        return new TokenVO(token, "Bearer ");
    }

    @Override
    public void logout(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        String jti = adminJwtTool.getJti(token);
        if (jti != null) {
            long ttl = adminJwtTool.getRemainingTTL(token);
            if (ttl > 0) {
                redisService.set(BLACKLIST_KEY_PREFIX + jti, "1", Duration.ofSeconds(ttl));
            }
        }
        log.info("管理员登出成功");
    }

    @Override
    public AdminInfoVO getAdminInfo(Long adminId) {
        AdminUser adminUser = adminUserMapper.selectById(adminId);
        if (adminUser == null) {
            throw new BadRequestException("管理员不存在");
        }
        // 获取角色
        var roles = roleService.getRolesByAdminId(adminId);
        List<Long> roleIds = roles.stream().map(r -> r.getId()).collect(Collectors.toList());
        List<String> roleNames = roles.stream().map(r -> r.getName()).collect(Collectors.toList());

        // 获取菜单树
        List<MenuNode> menus = menuService.getMenuTreeByRoleIds(roleIds);

        // 获取权限编码
        List<String> permissions = resourceService.getPermissionCodesByRoleIds(roleIds);
        // 超级管理员拥有全部权限
        if (roleIds.contains(1L)) {
            permissions.add("*");
        }

        AdminInfoVO vo = new AdminInfoVO();
        vo.setId(adminUser.getId());
        vo.setUsername(adminUser.getUsername());
        vo.setIcon(adminUser.getIcon());
        vo.setRoles(roleNames);
        vo.setMenus(menus);
        vo.setPermissions(permissions);
        return vo;
    }

    @Override
    public TokenVO refreshToken(String oldToken) {
        String newToken = adminJwtTool.refreshToken(oldToken);
        if (newToken == null) {
            return null;
        }
        return new TokenVO(newToken, "Bearer ");
    }
}
