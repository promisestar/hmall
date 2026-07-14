package com.hmall.user.controller;


import com.hmall.api.dto.DeductMoneyDTO;
import com.hmall.user.domain.dto.LoginByCodeDTO;
import com.hmall.user.domain.dto.LoginFormDTO;
import com.hmall.user.domain.dto.SendCodeDTO;
import com.hmall.user.domain.po.User;
import com.hmall.user.domain.vo.UserLoginVO;
import com.hmall.user.service.IUserService;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

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

    // ==================== 管理后台接口（admin-service 调用） ====================

    @ApiOperation("管理后台分页查询C端用户")
    @GetMapping("/page")
    public PageDTO<User> queryUserPage(PageQuery pageQuery,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Integer status) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like(User::getUsername, keyword)
                    .or().like(User::getPhone, keyword);
        }
        if (status != null) {
            wrapper.eq(User::getStatus, status);
        }
        wrapper.orderByDesc(User::getCreateTime);
        Page<User> page = userService.page(pageQuery.toMpPage("create_time", false), wrapper);
        // 清除密码
        page.getRecords().forEach(u -> u.setPassword(null));
        return PageDTO.of(page);
    }

    @ApiOperation("管理后台查询用户详情")
    @GetMapping("/{id}")
    public R<User> getUserById(@PathVariable Long id) {
        User user = userService.getById(id);
        if (user != null) {
            user.setPassword(null);
        }
        return R.ok(user);
    }

    @ApiOperation("修改用户状态(冻结/解冻)")
    @PostMapping("/status/{id}")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        User user = new User();
        user.setId(id);
        user.setStatus(status == 1 ? com.hmall.user.enums.UserStatus.NORMAL : com.hmall.user.enums.UserStatus.FROZEN);
        userService.updateById(user);
        return R.ok();
    }

    @ApiOperation("调整用户余额")
    @PostMapping("/balance/{id}")
    public R<Void> updateBalance(@PathVariable Long id, @RequestParam Integer delta) {
        User user = userService.getById(id);
        if (user == null) {
            return R.error("用户不存在");
        }
        int newBalance = (user.getBalance() != null ? user.getBalance() : 0) + delta;
        if (newBalance < 0) {
            return R.error("余额不足");
        }
        User update = new User();
        update.setId(id);
        update.setBalance(newBalance);
        userService.updateById(update);
        return R.ok();
    }
}

