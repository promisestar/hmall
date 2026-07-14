package com.hmall.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.admin.domain.po.Menu;
import com.hmall.admin.domain.vo.MenuNode;
import com.hmall.admin.mapper.MenuMapper;
import com.hmall.admin.mapper.RoleMenuRelMapper;
import com.hmall.admin.service.IMenuService;
import com.hmall.common.utils.BeanUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MenuServiceImpl extends ServiceImpl<MenuMapper, Menu> implements IMenuService {

    private final RoleMenuRelMapper roleMenuRelMapper;

    @Override
    public List<MenuNode> getMenuTree() {
        List<Menu> allMenus = list(new LambdaQueryWrapper<Menu>().orderByAsc(Menu::getSort));
        return buildTree(allMenus);
    }

    @Override
    public List<MenuNode> getMenuTreeByRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        // 查询角色关联的菜单 ID
        Set<Long> menuIds = new HashSet<>();
        for (Long roleId : roleIds) {
            menuIds.addAll(roleMenuRelMapper.selectMenuIdsByRoleId(roleId));
        }
        if (menuIds.isEmpty()) {
            return Collections.emptyList();
        }
        // 查询菜单详情，需要包含父级菜单以保证树结构完整
        List<Menu> allMenus = list(new LambdaQueryWrapper<Menu>().orderByAsc(Menu::getSort));
        Set<Long> needIds = new HashSet<>();
        for (Menu menu : allMenus) {
            if (menuIds.contains(menu.getId())) {
                needIds.add(menu.getId());
                // 向上查找父级
                Long parentId = menu.getParentId();
                while (parentId != null && parentId != 0 && !needIds.contains(parentId)) {
                    needIds.add(parentId);
                    for (Menu m : allMenus) {
                        if (m.getId().equals(parentId)) {
                            parentId = m.getParentId();
                            break;
                        }
                    }
                }
            }
        }
        List<Menu> filteredMenus = allMenus.stream()
                .filter(m -> needIds.contains(m.getId()))
                .collect(Collectors.toList());
        return buildTree(filteredMenus);
    }

    @Override
    @Transactional
    public void createMenu(Menu menu) {
        save(menu);
    }

    @Override
    @Transactional
    public void updateMenu(Long id, Menu menu) {
        menu.setId(id);
        updateById(menu);
    }

    @Override
    @Transactional
    public void deleteMenu(Long id) {
        // 递归删除子菜单
        List<Long> childIds = getChildIds(id);
        childIds.add(id);
        removeByIds(childIds);
    }

    private List<Long> getChildIds(Long parentId) {
        List<Menu> children = list(new LambdaQueryWrapper<Menu>().eq(Menu::getParentId, parentId));
        List<Long> result = new ArrayList<>();
        for (Menu child : children) {
            result.add(child.getId());
            result.addAll(getChildIds(child.getId()));
        }
        return result;
    }

    private List<MenuNode> buildTree(List<Menu> menus) {
        List<MenuNode> nodes = BeanUtils.copyList(menus, MenuNode.class);
        Map<Long, MenuNode> nodeMap = nodes.stream()
                .collect(Collectors.toMap(MenuNode::getId, n -> n));
        List<MenuNode> roots = new ArrayList<>();
        for (MenuNode node : nodes) {
            if (node.getParentId() == null || node.getParentId() == 0) {
                roots.add(node);
            } else {
                MenuNode parent = nodeMap.get(node.getParentId());
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(node);
                } else {
                    roots.add(node);
                }
            }
        }
        return roots;
    }
}
