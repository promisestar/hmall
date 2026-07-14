package com.hmall.admin.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 菜单树节点（用于前端动态菜单渲染）
 */
@Data
public class MenuNode {
    private Long id;
    private Long parentId;
    private String title;
    private String name;
    private String path;
    private String icon;
    private Integer level;
    private Integer sort;
    private Integer hidden;
    private List<MenuNode> children;
}
