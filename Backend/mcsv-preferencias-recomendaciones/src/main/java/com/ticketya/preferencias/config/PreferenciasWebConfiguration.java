package com.ticketya.preferencias.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.ticketya.preferencias.api.AvisoTokenInterceptor;
import com.ticketya.preferencias.security.PreferenciasIdentityInterceptor;

@Configuration
public class PreferenciasWebConfiguration implements WebMvcConfigurer {
    private final HandlerInterceptor preferenciasIdentityInterceptor =
            new PreferenciasIdentityInterceptor();
    private final HandlerInterceptor avisoTokenInterceptor;

    public PreferenciasWebConfiguration(AvisoTokenInterceptor avisoTokenInterceptor) {
        this.avisoTokenInterceptor = avisoTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(preferenciasIdentityInterceptor)
                .addPathPatterns("/preferencias/me", "/recomendaciones/me");
        registry.addInterceptor(avisoTokenInterceptor)
                .addPathPatterns("/internal/preferencias/eventos-publicados");
    }
}
