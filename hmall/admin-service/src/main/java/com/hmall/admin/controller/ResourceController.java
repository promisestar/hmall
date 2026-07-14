package com.hmall.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmall.admin.domain.po.Resource;
import com.hmall.admin.domain.po.ResourceCategory;
import com.hmall.admin.mapper.ResourceCategoryMapper;
import com.hmall.admin.service.IResourceService;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.domain.R;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Api(tags = "资源管理接口")
@RestController
@RequestMapping("/admin/resource")
@RequiredArgsConstructor
public class ResourceController {

    private final IResourceService resourceService;
    private final ResourceCategoryMapper resourceCategoryMapper;

    @ApiOperation("分页查询资源")
    @GetMapping("/list")
    public R<PageDTO<Resource>> list(PageQuery pageQuery,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) Long categoryId) {
        Page<Resource> page = resourceService.lambdaQuery()
                .like(keyword != null && !keyword.isEmpty(), Resource::getName, keyword)
                .eq(categoryId != null, Resource::getCategoryId, categoryId)
                .orderByDesc(Resource::getCreateTime)
                .page(pageQuery.toMpPage("create_time", false));
        return R.ok(PageDTO.of(page));
    }

    @ApiOperation("查询全部资源")
    @GetMapping("/listAll")
    public R<List<Resource>> listAll() {
        return R.ok(resourceService.listAll());
    }

    @ApiOperation("新增资源")
    @PostMapping("/create")
    public R<Void> create(@RequestBody Resource resource) {
        resourceService.save(resource);
        return R.ok();
    }

    @ApiOperation("更新资源")
    @PostMapping("/update/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody Resource resource) {
        resource.setId(id);
        resourceService.updateById(resource);
        return R.ok();
    }

    @ApiOperation("删除资源")
    @PostMapping("/delete/{id}")
    public R<Void> delete(@PathVariable Long id) {
        resourceService.removeById(id);
        return R.ok();
    }

    @ApiOperation("查询资源分类列表")
    @GetMapping("/category/listAll")
    public R<List<ResourceCategory>> listCategory() {
        return R.ok(resourceCategoryMapper.selectList(null));
    }
}
