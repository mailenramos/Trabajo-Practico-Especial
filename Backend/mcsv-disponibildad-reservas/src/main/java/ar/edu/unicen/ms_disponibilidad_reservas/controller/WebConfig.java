package ar.edu.unicen.ms_disponibilidad_reservas.controller;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final IdentidadReservaInterceptor identidad;
    public WebConfig(IdentidadReservaInterceptor identidad) { this.identidad = identidad; }
    @Override
    public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(identidad); }
}
