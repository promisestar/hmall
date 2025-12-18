package com.itheima.mp.service;

import com.itheima.mp.domain.po.Address;
import com.baomidou.mybatisplus.extension.service.IService;
import com.itheima.mp.domain.vo.AddressVO;
import com.itheima.mp.domain.vo.UserVO;

import java.util.List;

/**
* @author Raiden
* @description 针对表【address】的数据库操作Service
* @createDate 2025-10-27 19:35:12
*/
public interface AddressService extends IService<Address> {

    List<AddressVO> queryAddressByUserId(Long id);
}
