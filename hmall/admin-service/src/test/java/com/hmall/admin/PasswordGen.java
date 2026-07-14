package com.hmall.admin;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 生成 admin123 的正确 BCrypt 哈希（一次性工具，生成后可删除）
 * 右键直接 run main 即可
 */
public class PasswordGen {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        // 运行 5 次，取任意一个填入 SQL
        for (int i = 0; i < 5; i++) {
            String hash = encoder.encode("admin123");
            System.out.println("admin123 → " + hash + " (长度=" + hash.length() + ")");
        }
    }
}
