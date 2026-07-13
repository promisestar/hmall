package com.hmall.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.user.domain.po.Address;
import com.hmall.user.mapper.AddressMapper;
import com.hmall.user.service.IAddressService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
@Service
public class AddressServiceImpl extends ServiceImpl<AddressMapper, Address> implements IAddressService {

    @Override
    @Transactional
    public void setDefaultAddress(Long userId, Long addressId) {
        // 1.清除当前用户所有默认地址
        lambdaUpdate()
                .eq(Address::getUserId, userId)
                .set(Address::getIsDefault, 0)
                .update();
        // 2.如果指定了目标地址，设置其为默认
        if (addressId != null) {
            lambdaUpdate()
                    .eq(Address::getId, addressId)
                    .eq(Address::getUserId, userId)
                    .set(Address::getIsDefault, 1)
                    .update();
        }
    }

}
