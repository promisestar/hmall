package com.hmall.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmall.admin.domain.po.Menu;
import com.hmall.admin.domain.vo.MenuNode;

import java.util.List;

/**
 * 菜单服务
 */
public interface IMenuService extends IService<Menu> {

    /**
     * 获取菜单树（全量）
     */
    List<MenuNode> getMenuTree();

    /**
     * 根据角色 ID 列表获取菜单树（用于 GET /admin/info）
     */
    List<MenuNode> getMenuTreeByRoleIds(List<Long> roleIds);

    /**
     * 新增菜单
     */
    void createMenu(Menu menu);

    /**
     * 更新菜单
     */
    void updateMenu(Long id, Menu menu);

    /**
     * 删除菜单（同时删除子菜单）
     */
    void deleteMenu(Long id);
}
