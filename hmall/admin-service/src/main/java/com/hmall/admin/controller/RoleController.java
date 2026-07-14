package com.hmall.admin.controller;

import com.hmall.admin.domain.po.Role;
import com.hmall.admin.service.IRoleService;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Api(tags = "角色管理接口")
@RestController
@RequestMapping("/admin/role")
@RequiredArgsConstructor
public class RoleController {

    private final IRoleService roleService;

    @ApiOperation("分页查询角色")
    @GetMapping("/list")
    public R<PageDTO<Role>> list(PageQuery pageQuery, @RequestParam(required = false) String keyword) {
        Page<Role> page = roleService.page(pageQuery.toMpPage("sort", true));
        return R.ok(PageDTO.of(page));
    }

    @ApiOperation("查询全部角色")
    @GetMapping("/listAll")
    public R<List<Role>> listAll() {
        return R.ok(roleService.list());
    }

    @ApiOperation("新增角色")
    @PostMapping("/create")
    public R<Void> create(@RequestBody Role role) {
        roleService.save(role);
        return R.ok();
    }

    @ApiOperation("更新角色")
    @PostMapping("/update/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody Role role) {
        role.setId(id);
        roleService.updateById(role);
        return R.ok();
    }

    @ApiOperation("批量删除角色")
    @PostMapping("/delete")
    public R<Void> delete(@RequestBody List<Long> ids) {
        roleService.removeByIds(ids);
        return R.ok();
    }

    @ApiOperation("修改角色状态")
    @PostMapping("/updateStatus/{id}")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        Role role = new Role();
        role.setId(id);
        role.setStatus(status);
        roleService.updateById(role);
        return R.ok();
    }

    @ApiOperation("获取角色的菜单")
    @GetMapping("/listMenu/{roleId}")
    public R<List<Long>> listMenu(@PathVariable Long roleId) {
        return R.ok(roleService.getMenuIdsByRoleId(roleId));
    }

    @ApiOperation("获取角色的资源")
    @GetMapping("/listResource/{roleId}")
    public R<List<Long>> listResource(@PathVariable Long roleId) {
        return R.ok(roleService.getResourceIdsByRoleId(roleId));
    }

    @ApiOperation("给角色分配菜单")
    @PostMapping("/allocMenu")
    public R<Void> allocMenu(@RequestParam Long roleId, @RequestParam List<Long> menuIds) {
        roleService.allocMenus(roleId, menuIds);
        return R.ok();
    }

    @ApiOperation("给角色分配资源")
    @PostMapping("/allocResource")
    public R<Void> allocResource(@RequestParam Long roleId, @RequestParam List<Long> resourceIds) {
        roleService.allocResources(roleId, resourceIds);
        return R.ok();
    }
}
