package com.fisiosaas.platform;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PlatformWebConfig implements WebMvcConfigurer {
    private final ClinicAccessInterceptor clinicAccess;

    public PlatformWebConfig(ClinicAccessInterceptor clinicAccess) {
        this.clinicAccess = clinicAccess;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(clinicAccess).addPathPatterns("/api/v1/**")
            .excludePathPatterns("/api/v1/public/**", "/api/v1/auth/csrf", "/api/v1/admin/**");
    }
}
