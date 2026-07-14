package com.hmall.admin.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("menu")
public class Menu {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long parentId;
    private String title;
    private Integer level;
    private Integer sort;
    private String name;
    private String path;
    private String icon;
    private Integer hidden;
    private LocalDateTime createTime;
}
