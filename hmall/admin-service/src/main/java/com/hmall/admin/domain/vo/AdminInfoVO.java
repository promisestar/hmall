package com.hmall.admin.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * GET /admin/info 返回的管理员信息
 */
@Data
public class AdminInfoVO {
    private Long id;
    private String username;
    private String icon;
    private List<String> roles;
    private List<MenuNode> menus;
    private List<String> permissions;
}
