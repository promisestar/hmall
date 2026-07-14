package com.hmall.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.admin.domain.po.Resource;
import com.hmall.admin.mapper.ResourceMapper;
import com.hmall.admin.mapper.RoleResourceRelMapper;
import com.hmall.admin.service.IResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ResourceServiceImpl extends ServiceImpl<ResourceMapper, Resource> implements IResourceService {

    private final RoleResourceRelMapper roleResourceRelMapper;

    @Override
    public List<Resource> listAll() {
        return list();
    }

    @Override
    public Map<String, String> getResourceUrlMap() {
        List<Resource> resources = listAll();
        Map<String, String> map = new HashMap<>();
        for (Resource resource : resources) {
            map.put(resource.getUrl(), resource.getId() + ":" + resource.getName());
        }
        return map;
    }

    @Override
    public List<String> getPermissionCodesByRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> resourceIds = new HashSet<>();
        for (Long roleId : roleIds) {
            resourceIds.addAll(roleResourceRelMapper.selectResourceIdsByRoleId(roleId));
        }
        if (resourceIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Resource> resources = listByIds(resourceIds);
        return resources.stream()
                .map(r -> r.getId() + ":" + r.getName())
                .collect(Collectors.toList());
    }
}
