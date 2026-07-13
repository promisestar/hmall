package com.hmall.user.controller;


import com.hmall.common.exception.BadRequestException;
import com.hmall.common.utils.BeanUtils;
import com.hmall.common.utils.CollUtils;
import com.hmall.common.utils.UserContext;

import com.hmall.user.domain.dto.AddressDTO;
import com.hmall.user.domain.po.Address;
import com.hmall.user.service.IAddressService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/addresses")
@RequiredArgsConstructor
@Api(tags = "收货地址管理接口")
public class AddressController {

    private final IAddressService addressService;

    @ApiOperation("根据id查询地址")
    @GetMapping("{addressId}")
    public AddressDTO findAddressById(@ApiParam("地址id") @PathVariable("addressId") Long id) {
        // 1.根据id查询
        Address address = addressService.getById(id);
        // 2.判断当前用户
        Long userId = UserContext.getUser();
        if(!address.getUserId().equals(userId)){
            throw new BadRequestException("地址不属于当前登录用户");
        }
        return BeanUtils.copyBean(address, AddressDTO.class);
    }
    @ApiOperation("查询当前用户地址列表")
    @GetMapping
    public List<AddressDTO> findMyAddresses() {
        // 1.查询列表
        List<Address> list = addressService.query().eq("user_id", UserContext.getUser()).list();
        // 2.判空
        if (CollUtils.isEmpty(list)) {
            return CollUtils.emptyList();
        }
        // 3.转vo
        return BeanUtils.copyList(list, AddressDTO.class);
    }

    @ApiOperation("新增收货地址")
    @PostMapping
    public void saveAddress(@RequestBody AddressDTO addressDTO) {
        // 1.拷贝属性到PO
        Address address = BeanUtils.copyBean(addressDTO, Address.class);
        // 2.设置当前用户
        address.setUserId(UserContext.getUser());
        // 3.如果设为默认，先清除其他默认地址
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            addressService.setDefaultAddress(UserContext.getUser(), null);
        }
        // 4.保存
        addressService.save(address);
    }

    @ApiOperation("修改收货地址")
    @PutMapping("{addressId}")
    public void updateAddress(@ApiParam("地址id") @PathVariable("addressId") Long id,
                              @RequestBody AddressDTO addressDTO) {
        // 1.查询原地址，判断当前用户
        Long userId = UserContext.getUser();
        Address origin = addressService.getById(id);
        if (!origin.getUserId().equals(userId)) {
            throw new BadRequestException("地址不属于当前登录用户");
        }
        // 2.拷贝属性到新PO
        Address address = BeanUtils.copyBean(addressDTO, Address.class);
        address.setId(id);
        address.setUserId(userId);
        // 3.如果设为默认，先清除其他默认地址
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            addressService.setDefaultAddress(userId, id);
        }
        // 4.更新
        addressService.updateById(address);
    }

    @ApiOperation("删除收货地址")
    @DeleteMapping("{addressId}")
    public void deleteAddress(@ApiParam("地址id") @PathVariable("addressId") Long id) {
        // 1.查询地址
        Address address = addressService.getById(id);
        // 2.判断当前用户
        if (!address.getUserId().equals(UserContext.getUser())) {
            throw new BadRequestException("地址不属于当前登录用户");
        }
        // 3.删除
        addressService.removeById(id);
    }

    @ApiOperation("设置默认收货地址")
    @PutMapping("default/{addressId}")
    public void setDefaultAddress(@ApiParam("地址id") @PathVariable("addressId") Long id) {
        // 1.查询地址
        Address address = addressService.getById(id);
        // 2.判断当前用户
        Long userId = UserContext.getUser();
        if (!address.getUserId().equals(userId)) {
            throw new BadRequestException("地址不属于当前登录用户");
        }
        // 3.设置默认
        addressService.setDefaultAddress(userId, id);
    }
}
