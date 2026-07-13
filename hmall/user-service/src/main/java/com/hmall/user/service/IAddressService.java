package com.hmall.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmall.user.domain.po.Address;


/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
public interface IAddressService extends IService<Address> {

    /**
     * 设置默认收货地址：先清除当前用户所有默认地址，再设置目标地址为默认
     *
     * @param userId    用户ID
     * @param addressId 地址ID，为 null 时仅清除默认
     */
    void setDefaultAddress(Long userId, Long addressId);

}
