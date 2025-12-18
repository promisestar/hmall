package com.itheima.mp.service;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.itheima.mp.domain.po.User;
import com.itheima.mp.domain.po.UserInfo;
import com.itheima.mp.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ClassName: UserServiceTest
 * Package: com.itheima.mp.service
 * Description:
 *
 * @Author Raiden
 * @Create 2025/10/23 20:22
 * @Version 1.0
 */
@SpringBootTest
public class UserServiceTest {

    @Autowired
    private UserServiceImpl userService;
    @Test
    void testGetById() {
        List<User> users = userService.listByIds(List.of(1L, 2L, 3L));
        users.forEach(System.out::println);
    }

    @Test
    void testSave(){
        User user = new User();
        //user.setId(5L);
        user.setUsername("HanMei");
        user.setPassword("123");
        user.setPhone("18688990013");
        user.setBalance(200);
        user.setInfo(UserInfo.of(21, "英文老师", "female"));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userService.save(user);
    }

    @Test
    void testPageQuery(){
        long pageNo = 1, pageSize = 2;
        Page<User> page = Page.of(pageNo, pageSize);
        page.addOrder(OrderItem.asc("balance"));
        page.addOrder(OrderItem.asc("id"));
        userService.page(page);
        long total = page.getTotal();
        System.out.println("total = " + total);
        long pages = page.getPages();
        System.out.println("pages = " + pages);
        List<User> records = page.getRecords();
        records.forEach(System.out::println);
    }
}
