package com.itheima.mp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.itheima.mp.domain.enums.UserStatus;
import com.itheima.mp.domain.po.Address;
import com.itheima.mp.domain.po.User;
import com.itheima.mp.domain.vo.AddressVO;
import com.itheima.mp.domain.vo.UserVO;
import com.itheima.mp.service.AddressService;
import com.itheima.mp.mapper.AddressMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
* @author Raiden
* @description 针对表【address】的数据库操作Service实现
* @createDate 2025-10-27 19:35:12
*/
@Service
public class AddressServiceImpl extends ServiceImpl<AddressMapper, Address>
    implements AddressService{

    @Override
    public List<AddressVO> queryAddressByUserId(Long id) {
        // 1. 根据用户id查询用户
        User user = Db.getById(id, User.class);
        if(user == null || user.getStatus() == UserStatus.FROZEN){
            throw new RuntimeException("用户不存在或状态异常");
        }
        List<Address> addresses = this.lambdaQuery().eq(Address::getUserId, id).list();
        return BeanUtil.copyToList(addresses, AddressVO.class);
    }
}




