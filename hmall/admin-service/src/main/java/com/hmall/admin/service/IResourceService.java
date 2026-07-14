package com.hmall.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmall.admin.domain.po.Resource;

import java.util.List;
import java.util.Map;

/**
 * 资源(权限)服务
 */
public interface IResourceService extends IService<Resource> {

    /**
     * 获取全部资源列表（用于动态权限加载）
     */
    List<Resource> listAll();

    /**
     * 获取 URL→权限编码 的映射（DynamicSecurityService 使用）
     */
    Map<String, String> getResourceUrlMap();

    /**
     * 根据角色 ID 列表获取权限编码列表
     */
    List<String> getPermissionCodesByRoleIds(List<Long> roleIds);
}
