package com.hmall.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * 确保日志目录存在，解决 Logback 1.2.x RollingFileAppender 不会自动创建父目录的问题。
 * <p>
 * 若不创建，FileAppender 在父目录缺失时静默失败（控制台有日志，api.log/hmall.log 永远为空）。
 */
@Configuration
public class LogDirectoryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LogDirectoryInitializer.class);

    @Override
    public void run(ApplicationArguments args) {
        String logPath = System.getProperty("LOG_PATH", "./logs");
        File dir = new File(logPath);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (created) {
                log.info("日志目录已创建: {}", dir.getAbsolutePath());
            } else {
                log.error("日志目录创建失败: {}", dir.getAbsolutePath());
            }
        }
    }
}
