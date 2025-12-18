package com.itheima.mp.domain.po;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ClassName: UserInfo
 * Package: com.itheima.mp.domain.po
 * Description:
 *
 * @Author Raiden
 * @Create 2025/10/29 20:26
 * @Version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class UserInfo {
    private Integer age;
    private String intro;
    private String gender;
}
