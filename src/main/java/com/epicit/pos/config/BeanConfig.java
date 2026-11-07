package com.epicit.pos.config;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfig {

    @Bean
    AppPaths appPaths(@Value("${epicit.data-dir}") String dataDir) {
        return new AppPaths(Path.of(dataDir));
    }

    @Bean
    AppConfig appConfig(AppPaths paths) {
        return new AppConfig(paths);
    }
}
