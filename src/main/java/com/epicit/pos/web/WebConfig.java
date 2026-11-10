package com.epicit.pos.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final OwnerAuthInterceptor ownerAuth;

    public WebConfig(OwnerAuthInterceptor ownerAuth) {
        this.ownerAuth = ownerAuth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(ownerAuth).addPathPatterns("/api/owner/**");
    }
}
