package com.itheima.mp.controller;

import cn.hutool.core.bean.BeanUtil;
import com.itheima.mp.domain.dto.PageDTO;
import com.itheima.mp.domain.dto.UserFormDTO;
import com.itheima.mp.domain.po.User;
import com.itheima.mp.domain.query.UserQuery;
import com.itheima.mp.domain.vo.UserVO;
import com.itheima.mp.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.annotations.Param;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ClassName: UserController
 * Package: com.itheima.mp.controller
 * Description:
 *
 * @Author Raiden
 * @Create 2025/10/23 20:51
 * @Version 1.0
 */
@Tag(name = "用户管理接口")
@RequestMapping("/users")
@RestController
@RequiredArgsConstructor
public class UserController {

    // 使用构造函数注入Service
    private final UserService userService;

    @Operation(summary = "新增用户接口")
    @PostMapping
    public void saveUser(@RequestBody UserFormDTO userDTO){
        // 1. 将DTO拷贝为PO
        User user = BeanUtil.copyProperties(userDTO, User.class);
        // 2. 新增
        userService.save(user);
    }

    @Operation(summary = "删除用户接口")
    @DeleteMapping("/{id}")
    public void deleteById(@Parameter(description = "用户id") @PathVariable Long id){
        userService.removeById(id);
    }

    @Operation(summary = "根据id查询用户接口")
    @GetMapping("/{id}")
    public UserVO queryById(@Parameter(description = "用户id") @PathVariable Long id){
        return userService.queryUserAndAddressById(id);
    }

    @Operation(summary = "根据id集合查询用户接口")
    @GetMapping
    public List<UserVO> queryByIds(@Parameter(description = "用户id集合") @RequestParam List<Long> ids){
        return userService.queryUserAndAddressByIds(ids);
    }
    @Operation(summary = "根据复杂条件查询用户接口")
    @GetMapping("/list")
    public List<UserVO> queryByConditions(@ParameterObject UserQuery userQuery){
        List<User> userList = userService.queryByConditions(userQuery.getName(), userQuery.getStatus(), userQuery.getMaxBalance(), userQuery.getMinBalance());
        return BeanUtil.copyToList(userList, UserVO.class);
    }

    @Operation(summary = "扣减用户金额")
    @PutMapping("/{id}/deduction/{money}")
    public void deductionBalance(
            @Parameter(description = "用户id") @PathVariable Long id,
            @Parameter(description = "金额") @PathVariable Integer money
    ){
        userService.deductionBalance(id, money);
    }
    @Operation(summary = "根据条件分页查询用户接口")
    @GetMapping("/page")
    public PageDTO<UserVO> queryUsersPage(
            @Parameter(description = "分页查询实体") @ParameterObject UserQuery userQuery){
        return userService.queryUsersPage(userQuery);
    }
}
