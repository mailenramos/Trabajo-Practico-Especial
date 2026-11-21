package com.ticketya.msvc.entradas.config;

import com.ticketya.msvc.entradas.seguridad.UsuarioActualResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.util.List;

@Configuration
@EnableConfigurationProperties(EntradasProperties.class)
public class AppConfig implements WebMvcConfigurer {

    private final UsuarioActualResolver usuarioActualResolver;

    public AppConfig(UsuarioActualResolver usuarioActualResolver) {
        this.usuarioActualResolver = usuarioActualResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(usuarioActualResolver);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Cliente HTTP hacia MS1 con timeouts cortos: una demora rechaza la validación con 503. */
    @Bean
    RestClient ms1RestClient(EntradasProperties propiedades) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(propiedades.ms1().connectTimeout());
        fabrica.setReadTimeout(propiedades.ms1().readTimeout());
        return RestClient.builder()
                .baseUrl(propiedades.ms1().url())
                .requestFactory(fabrica)
                .build();
    }
}
