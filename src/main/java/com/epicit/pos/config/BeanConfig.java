package com.epicit.pos.config;

import com.epicit.pos.EpicItPosApplication;
import java.nio.file.Path;
import java.time.Clock;
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

    /** One clock, pinned to Africa/Kigali (A13); services take it so time is testable and never laptop-zone dependent. */
    @Bean
    Clock clock() {
        return Clock.system(EpicItPosApplication.APP_ZONE);
    }
}
