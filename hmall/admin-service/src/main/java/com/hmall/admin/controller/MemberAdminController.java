package com.hmall.admin.controller;

import com.hmall.admin.feign.UserFeignClient;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Api(tags = "C端用户管理接口")
@RestController
@RequestMapping("/admin/member")
@RequiredArgsConstructor
public class MemberAdminController {

    private final UserFeignClient userFeignClient;

    @ApiOperation("分页查询C端用户")
    @GetMapping("/list")
    public R<PageDTO<Object>> list(PageQuery pageQuery,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Integer status) {
        return R.ok(userFeignClient.queryUserByPage(pageQuery, keyword, status));
    }

    @ApiOperation("用户详情")
    @GetMapping("/{id}")
    public R<Object> getInfo(@PathVariable Long id) {
        return userFeignClient.queryUserById(id);
    }

    @ApiOperation("修改用户状态(冻结/解冻)")
    @PostMapping("/status/{id}")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        return userFeignClient.updateStatus(id, status);
    }

    @ApiOperation("调整用户余额")
    @PostMapping("/balance/{id}")
    public R<Void> updateBalance(@PathVariable Long id, @RequestParam Integer delta) {
        return userFeignClient.updateBalance(id, delta);
    }
}
