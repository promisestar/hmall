package com.hmall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmall.admin.domain.po.AdminUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {
}
