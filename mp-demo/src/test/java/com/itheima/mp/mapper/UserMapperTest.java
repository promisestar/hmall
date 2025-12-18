package com.itheima.mp.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.itheima.mp.domain.po.User;
import com.itheima.mp.domain.po.UserInfo;
import net.minidev.json.writer.UpdaterMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

@SpringBootTest
class UserMapperTest {

    @Autowired
    private UserMapper userMapper;

    @Test
    void testInsert() {
        User user = new User();
        //user.setId(5L);
        user.setUsername("HanMeiMei");
        user.setPassword("123");
        user.setPhone("18688990012");
        user.setBalance(200);
        user.setInfo(UserInfo.of(21, "英文老师", "female"));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);
    }

    @Test
    void testSelectById() {
        User user = userMapper.queryUserById(5L);
        System.out.println("user = " + user);
    }


    @Test
    void testQueryByIds() {
        List<User> users = userMapper.selectBatchIds(List.of(1L, 2L, 3L, 4L));
        users.forEach(System.out::println);
    }

    @Test
    void testUpdateById() {
        User user = new User();
        user.setId(5L);
        user.setBalance(20000);
        userMapper.updateById(user);
    }

    @Test
    void testDeleteUser() {
        userMapper.deleteById(5L);
    }

    // 测试warpper
    @Test
    void testQueryWrapper() {
        QueryWrapper<User> wrapper = new QueryWrapper<User>()
                .select("id", "username", "info", "balance")
                .like("username", "o")
                .ge("balance", 1000);
        List<User> users = userMapper.selectList(wrapper);
        users.forEach(System.out::println);
    }
    @Test
    void testLambdaQueryWrapper() {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .select(User::getId, User::getUsername, User::getInfo, User::getBalance)
                .like(User::getUsername, "o")
                .ge(User::getBalance, 1000);
        List<User> users = userMapper.selectList(wrapper);
        users.forEach(System.out::println);
    }
    @Test
    void testUpdateByQueryWarpper() {
        QueryWrapper<User> wrapper = new QueryWrapper<User>()
                .eq("username", "jack");
        User user = new User();
        user.setBalance(2000);
        userMapper.update(user, wrapper);
    }

    @Test
    void testUpdateWrapper() {
        List<Long> ids = List.of(1L, 2L, 3L);
        UpdateWrapper<User> wrapper = new UpdateWrapper<User>()
                .setSql("balance = balance + 200")
                .in("id", ids);
        userMapper.update(null, wrapper);
    }

    @Test
    void testQueryWrapper2(){
        List<Long> ids = List.of(1L, 2L, 3L);
        int amount = 200;
        QueryWrapper<User> wrapper = new QueryWrapper<User>()
                .in("id", ids);
        userMapper.updateBalanceById(wrapper, amount);
    }
}