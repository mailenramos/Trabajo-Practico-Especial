package com.ticketya.gestionlugares.reservas.http;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.ticketya.gestionlugares.trazabilidad.CorrelacionHttpInterceptor;

@Configuration
@EnableConfigurationProperties(ReservasProperties.class)
public class ReservasHttpConfiguration {

    @Bean
    RestClient reservasRestClient(RestClient.Builder builder, ReservasProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.clone().baseUrl(properties.getBaseUrl().toString())
                .requestFactory(requestFactory)
                .requestInterceptor(new CorrelacionHttpInterceptor())
                .build();
    }
}
