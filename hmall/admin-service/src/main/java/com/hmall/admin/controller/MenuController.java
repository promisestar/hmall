package com.hmall.admin.controller;

import com.hmall.admin.domain.po.Menu;
import com.hmall.admin.domain.vo.MenuNode;
import com.hmall.admin.service.IMenuService;
import com.hmall.common.domain.R;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Api(tags = "菜单管理接口")
@RestController
@RequestMapping("/admin/menu")
@RequiredArgsConstructor
public class MenuController {

    private final IMenuService menuService;

    @ApiOperation("获取菜单树")
    @GetMapping("/tree")
    public R<List<MenuNode>> tree() {
        return R.ok(menuService.getMenuTree());
    }

    @ApiOperation("新增菜单")
    @PostMapping("/create")
    public R<Void> create(@RequestBody Menu menu) {
        menuService.createMenu(menu);
        return R.ok();
    }

    @ApiOperation("更新菜单")
    @PostMapping("/update/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody Menu menu) {
        menuService.updateMenu(id, menu);
        return R.ok();
    }

    @ApiOperation("删除菜单")
    @PostMapping("/delete/{id}")
    public R<Void> delete(@PathVariable Long id) {
        menuService.deleteMenu(id);
        return R.ok();
    }
}
