package com.hmall.admin.controller;

import com.hmall.admin.domain.dto.AdminLoginDTO;
import com.hmall.admin.domain.vo.AdminInfoVO;
import com.hmall.admin.domain.vo.TokenVO;
import com.hmall.admin.service.IAdminAuthService;
import com.hmall.common.domain.R;
import com.hmall.common.utils.UserContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@Api(tags = "管理员认证接口")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final IAdminAuthService adminAuthService;

    @ApiOperation("管理员登录")
    @PostMapping("/login")
    public R<TokenVO> login(@RequestBody @Valid AdminLoginDTO loginDTO) {
        return R.ok(adminAuthService.login(loginDTO));
    }

    @ApiOperation("管理员登出")
    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        adminAuthService.logout(token);
        return R.ok();
    }

    @ApiOperation("获取当前管理员信息")
    @GetMapping("/info")
    public R<AdminInfoVO> info() {
        Long adminId = UserContext.getUser();
        return R.ok(adminAuthService.getAdminInfo(adminId));
    }

    @ApiOperation("刷新token")
    @GetMapping("/refreshToken")
    public R<TokenVO> refreshToken(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        TokenVO tokenVO = adminAuthService.refreshToken(token);
        return R.ok(tokenVO);
    }
}
