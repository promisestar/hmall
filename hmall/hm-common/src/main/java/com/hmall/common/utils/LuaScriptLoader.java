package com.hmall.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Lua 脚本加载工具：从 classpath 读取 .lua 文件内容
 */
@Slf4j
public final class LuaScriptLoader {

    private LuaScriptLoader() {
    }

    /**
     * 从 classpath 加载 Lua 脚本文件
     *
     * @param classpath 脚本相对于 classpath 的路径，如 "lua/add_cart.lua"
     * @return 脚本文本内容
     */
    public static String load(String classpath) {
        try {
            ClassPathResource resource = new ClassPathResource(classpath);
            byte[] bytes = resource.getInputStream().readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("加载 Lua 脚本失败: {}", classpath, e);
            throw new RuntimeException("Failed to load Lua script: " + classpath, e);
        }
    }
}
