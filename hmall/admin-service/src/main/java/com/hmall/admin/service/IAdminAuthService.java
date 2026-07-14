package com.hmall.admin.service;

import com.hmall.admin.domain.dto.AdminLoginDTO;
import com.hmall.admin.domain.vo.AdminInfoVO;
import com.hmall.admin.domain.vo.TokenVO;

/**
 * 管理员认证服务
 */
public interface IAdminAuthService {
    /**
     * 管理员登录
     */
    TokenVO login(AdminLoginDTO loginDTO);

    /**
     * 管理员登出
     */
    void logout(String token);

    /**
     * 获取当前登录管理员信息（含菜单和权限）
     */
    AdminInfoVO getAdminInfo(Long adminId);

    /**
     * 刷新 token
     */
    TokenVO refreshToken(String oldToken);
}
