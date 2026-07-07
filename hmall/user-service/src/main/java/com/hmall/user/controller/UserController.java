package com.hmall.user.controller;


import com.hmall.api.dto.DeductMoneyDTO;
import com.hmall.user.domain.dto.LoginFormDTO;
import com.hmall.user.domain.vo.UserLoginVO;
import com.hmall.user.service.IUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@Api(tags = "用户相关接口")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final IUserService userService;

    @ApiOperation("用户登录接口")
    @PostMapping("login")
    public UserLoginVO login(@RequestBody @Validated LoginFormDTO loginFormDTO){
        return userService.login(loginFormDTO);
    }

    @ApiOperation("扣减余额")
    @PostMapping("/money/deduct")
    public void deductMoney(@RequestBody @Valid DeductMoneyDTO deductMoneyDTO){
        userService.deductMoney(deductMoneyDTO.getPw(), deductMoneyDTO.getAmount());
    }
}

