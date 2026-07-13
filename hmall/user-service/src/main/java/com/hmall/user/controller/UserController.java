package com.hmall.user.controller;


import com.hmall.api.dto.DeductMoneyDTO;
import com.hmall.user.domain.dto.LoginByCodeDTO;
import com.hmall.user.domain.dto.LoginFormDTO;
import com.hmall.user.domain.dto.SendCodeDTO;
import com.hmall.user.domain.vo.UserLoginVO;
import com.hmall.user.service.IUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@Api(tags = "用户相关接口")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final IUserService userService;

    // ==================== 密码登录 ====================

    @ApiOperation("用户登录接口")
    @PostMapping("login")
    public UserLoginVO login(@RequestBody @Validated LoginFormDTO loginFormDTO){
        return userService.login(loginFormDTO);
    }

    // ==================== 3.5 验证码存储 ====================

    @ApiOperation("发送短信验证码")
    @PostMapping("/code")
    public void sendCode(@RequestBody @Validated SendCodeDTO sendCodeDTO) {
        userService.sendCode(sendCodeDTO.getPhone());
    }

    @ApiOperation("验证码登录")
    @PostMapping("/login/code")
    public UserLoginVO loginByCode(@RequestBody @Validated LoginByCodeDTO loginByCodeDTO) {
        return userService.loginByCode(loginByCodeDTO);
    }

    // ==================== 余额扣减 ====================

    @ApiOperation("扣减余额")
    @PostMapping("/money/deduct")
    public void deductMoney(@RequestBody @Valid DeductMoneyDTO deductMoneyDTO){
        userService.deductMoney(deductMoneyDTO.getPw(), deductMoneyDTO.getAmount());
    }

    // ==================== 3.6 Token 黑名单（登出） ====================

    @ApiOperation("用户登出")
    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        userService.logout(token);
    }
}

