package com.itheima.mp.controller;

import com.itheima.mp.domain.vo.AddressVO;
import com.itheima.mp.domain.vo.UserVO;
import com.itheima.mp.service.AddressService;
import com.itheima.mp.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ClassName: AddressController
 * Package: com.itheima.mp.controller
 * Description:
 *
 * @Author Raiden
 * @Create 2025/10/27 20:42
 * @Version 1.0
 */
@Tag(name = "用户收货地址管理接口")
@RequestMapping("/address")
@RestController
@RequiredArgsConstructor
public class AddressController {
    // 使用构造函数注入Service
    private final AddressService addressService;

    @Operation(summary = "根据id查询用户收货地址接口")
    @GetMapping("/{id}")
    public List<AddressVO> queryById(@Parameter(description = "用户id") @PathVariable Long id){
        return addressService.queryAddressByUserId(id);
    }
}
