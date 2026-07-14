package com.hmall.admin.controller;

import com.hmall.admin.domain.dto.AdminUserDTO;
import com.hmall.admin.service.IAdminUserService;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Api(tags = "管理员管理接口")
@RestController
@RequestMapping("/admin/admin")
@RequiredArgsConstructor
public class AdminUserController {

    private final IAdminUserService adminUserService;

    @ApiOperation("分页查询管理员列表")
    @GetMapping("/list")
    public R<PageDTO<AdminUserDTO>> list(PageQuery pageQuery,
                                           @RequestParam(required = false) String keyword) {
        return R.ok(adminUserService.queryAdminPage(pageQuery, keyword));
    }

    @ApiOperation("查询管理员详情")
    @GetMapping("/{id}")
    public R<AdminUserDTO> getInfo(@PathVariable Long id) {
        return R.ok(adminUserService.queryAdminById(id));
    }

    @ApiOperation("新增管理员")
    @PostMapping
    public R<Void> create(@RequestBody AdminUserDTO adminUserDTO) {
        adminUserService.createAdmin(adminUserDTO);
        return R.ok();
    }

    @ApiOperation("更新管理员信息")
    @PostMapping("/update/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody AdminUserDTO adminUserDTO) {
        adminUserService.updateAdmin(id, adminUserDTO);
        return R.ok();
    }

    @ApiOperation("删除管理员")
    @PostMapping("/delete/{id}")
    public R<Void> delete(@PathVariable Long id) {
        adminUserService.deleteAdmin(id);
        return R.ok();
    }

    @ApiOperation("修改密码")
    @PostMapping("/updatePassword")
    public R<Void> updatePassword(@RequestParam String oldPassword,
                                   @RequestParam String newPassword) {
        adminUserService.updatePassword(oldPassword, newPassword);
        return R.ok();
    }

    @ApiOperation("修改启用状态")
    @PostMapping("/updateStatus/{id}")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        adminUserService.updateStatus(id, status);
        return R.ok();
    }

    @ApiOperation("给管理员分配角色")
    @PostMapping("/role/update")
    public R<Void> allocRoles(@RequestParam Long adminId, @RequestParam List<Long> roleIds) {
        adminUserService.allocRoles(adminId, roleIds);
        return R.ok();
    }

    @ApiOperation("获取管理员的角色列表")
    @GetMapping("/role/{adminId}")
    public R<List<Long>> getRoles(@PathVariable Long adminId) {
        return R.ok(adminUserService.getRoleIdsByAdminId(adminId));
    }
}
