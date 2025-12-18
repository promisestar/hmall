package com.itheima.mp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.itheima.mp.domain.dto.PageDTO;
import com.itheima.mp.domain.enums.UserStatus;
import com.itheima.mp.domain.po.Address;
import com.itheima.mp.domain.po.User;
import com.itheima.mp.domain.query.UserQuery;
import com.itheima.mp.domain.vo.AddressVO;
import com.itheima.mp.domain.vo.UserVO;
import com.itheima.mp.mapper.UserMapper;
import com.itheima.mp.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ClassName: UserServiceImpl
 * Package: com.itheima.mp.service.impl
 * Description:
 *
 * @Author Raiden
 * @Create 2025/10/23 20:20
 * @Version 1.0
 */
@Service
@Transactional
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    @Override
    public void deductionBalance(Long id, Integer money) {
        // 1. 查询用户
        User user = this.getById(id);
        // 2. 检查用户是否存在或状态是否正常
        if(user == null || user.getStatus() == UserStatus.FROZEN){
            throw new RuntimeException("用户不存在或状态不正常");
        }
        // 3. 检查余额是否充足
        if(user.getBalance() < money){
            throw new RuntimeException("余额不足");
        }
        // 4. 扣除余额
        // baseMapper.deductionBalance(id, money);
        int remainBalance = user.getBalance() - money;
        lambdaUpdate()
                .set(User::getBalance, remainBalance)
                .set(remainBalance == 0, User::getStatus, UserStatus.FROZEN)
                .eq(User::getId, id)
                .eq(User::getBalance, user.getBalance()) //乐观锁，在更新时，用户的余额必须与我此前查询到的余额一致
                .update();
    }

    @Override
    public List<User> queryByConditions(String name, Integer status, Integer maxBalance, Integer minBalance) {
        return lambdaQuery()
                .like(name != null, User::getUsername, name)
                .eq(status != null, User::getStatus, status)
                .gt(minBalance != null, User::getBalance, minBalance)
                .lt(maxBalance != null, User::getBalance, maxBalance)
                .list();
    }

    @Override
    public UserVO queryUserAndAddressById(Long id) {
        // 1. 查询用户
        User user = this.getById(id);
        if(user == null || user.getStatus() == UserStatus.FROZEN){
            throw new RuntimeException("用户不存在或状态不正常");
        }
        // 2. 创建UserVO对象
        UserVO userVO = BeanUtil.copyProperties(user, UserVO.class);

        // 3. 查询地址信息
        List<Address> addresses = Db.lambdaQuery(Address.class).eq(Address::getUserId, id).list();
        // 4. 判断是否查询到地址信息
        if(CollUtil.isNotEmpty(addresses)){
            List<AddressVO> addressesVO = BeanUtil.copyToList(addresses, AddressVO.class);
            userVO.setAddresses(addressesVO);
        }
        return userVO;
    }

    @Override
    public List<UserVO> queryUserAndAddressByIds(List<Long> ids) {
        // 1. 批量查询用户
        List<User> users = this.listByIds(ids);
        if(CollUtil.isEmpty(users)){
            return Collections.emptyList();
        }
        // 2. 查询地址
        // 2.1 先获取查询到的用户的id,并利用获得的id查询地址
        List<Long> userIds = users.stream().map(User::getId).collect(Collectors.toList());
        List<Address> addresses = Db.lambdaQuery(Address.class).in(Address::getUserId, userIds).list();
        // 2.2 转换类型
        List<AddressVO> addressesVOList = BeanUtil.copyToList(addresses, AddressVO.class);
        // 2.3 对地址进行分组
        Map<Long, List<AddressVO>> addressMap = new HashMap<>(0);
        if(CollUtil.isNotEmpty(addressesVOList)){
            addressMap = addressesVOList.stream().collect(Collectors.groupingBy(AddressVO::getUserId));
        }
        // 3. 转换为最后需要的UserVO类型
        List<UserVO> list = new ArrayList<>(users.size());
        for(User user: users){
            UserVO userVO = BeanUtil.copyProperties(user, UserVO.class);
            list.add(userVO);
            userVO.setAddresses(addressMap.get(user.getId()));
        }
        return list;
    }

    @Override
    public PageDTO<UserVO> queryUsersPage(UserQuery userQuery) {
        // 1. 获取查询
        String name = userQuery.getName();
        Integer status = userQuery.getStatus();
//        // 2. 创建分页对象
//        Page<User> page = new Page<>(userQuery.getPageNo(), userQuery.getPageSize());
//        // 2.1 设置排序条件
//        if(StrUtil.isNotBlank(userQuery.getSortBy())){
//            page.addOrder(userQuery.isAsc() ? OrderItem.asc(userQuery.getSortBy()) : OrderItem.desc(userQuery.getSortBy()));
//        }else{
//            page.addOrder(OrderItem.desc("update_time"));
//        }
        Page<User> page = userQuery.toMpPageDefaultSortByUpdateTime();
        // 3. 查询用户数据
        Page<User> p = lambdaQuery()
                .like(name != null, User::getUsername, name)
                .eq(status != null, User::getStatus, status)
                .page(page);
        // 4. 封装查询结果为vo
//        PageDTO<UserVO> dto = new PageDTO<>();
//        dto.setPages(p.getPages());
//        dto.setTotal(p.getTotal());
//        List<User> records = p.getRecords();
//        if(CollUtil.isEmpty(records)){
//            dto.setList(Collections.emptyList());
//            return dto;
//        }
//        dto.setList(BeanUtil.copyToList(records, UserVO.class));
        return PageDTO.of(p, user -> {
            // 进行类型转换
            UserVO userVO = BeanUtil.copyProperties(user, UserVO.class);
            // 进行特殊处理
            userVO.setUsername(userVO.getUsername().substring(0, userVO.getUsername().length() - 2) + "**");
            return userVO;
        });
    }
}
