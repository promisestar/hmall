package com.hmall.admin.domain.dto;

import com.hmall.admin.domain.po.AdminUser;
import lombok.Data;

import java.util.List;

@Data
public class AdminUserDTO {
    private Long id;
    private String username;
    private String icon;
    private String email;
    private String nickName;
    private String note;
    private Integer status;
    private String password;
    private String createTime;
    private String loginTime;
    private List<Long> roleIds;

    public static AdminUserDTO fromPO(AdminUser po) {
        AdminUserDTO dto = new AdminUserDTO();
        dto.setId(po.getId());
        dto.setUsername(po.getUsername());
        dto.setIcon(po.getIcon());
        dto.setEmail(po.getEmail());
        dto.setNickName(po.getNickName());
        dto.setNote(po.getNote());
        dto.setStatus(po.getStatus());
        dto.setCreateTime(po.getCreateTime() != null ? po.getCreateTime().toString() : null);
        dto.setLoginTime(po.getLoginTime() != null ? po.getLoginTime().toString() : null);
        return dto;
    }
}
