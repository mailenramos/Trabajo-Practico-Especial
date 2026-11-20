package com.ticketya.gestionlugares.usuarios.http;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.ticketya.gestionlugares.trazabilidad.CorrelacionHttpInterceptor;

@Configuration
@EnableConfigurationProperties(UsuariosProperties.class)
public class UsuariosHttpConfiguration {
    @Bean
    RestClient usuariosRestClient(RestClient.Builder builder, UsuariosProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        RestClient.Builder cliente = builder.clone().requestFactory(factory)
                .requestInterceptor(new CorrelacionHttpInterceptor());
        if (properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()) {
            cliente.baseUrl(properties.getBaseUrl());
        }
        return cliente.build();
    }
}
