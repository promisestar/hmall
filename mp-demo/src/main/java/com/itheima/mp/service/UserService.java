package com.itheima.mp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.itheima.mp.domain.dto.PageDTO;
import com.itheima.mp.domain.po.User;
import com.itheima.mp.domain.query.UserQuery;
import com.itheima.mp.domain.vo.UserVO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ClassName: UserService
 * Package: com.itheima.mp.service
 * Description:
 *
 * @Author Raiden
 * @Create 2025/10/23 20:19
 * @Version 1.0
 */

public interface UserService extends IService<User> {
    void deductionBalance(Long id, Integer money);

    List<User> queryByConditions(String name, Integer status, Integer maxBalance, Integer minBalance);

    UserVO queryUserAndAddressById(Long id);

    List<UserVO> queryUserAndAddressByIds(List<Long> ids);

    PageDTO<UserVO> queryUsersPage(UserQuery userQuery);
}
