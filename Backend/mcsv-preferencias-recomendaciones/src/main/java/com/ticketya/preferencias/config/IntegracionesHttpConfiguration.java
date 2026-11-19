package com.ticketya.preferencias.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.ticketya.preferencias.integration.compras.ComprasProperties;
import com.ticketya.preferencias.integration.eventos.EventosProperties;

@Configuration
@EnableConfigurationProperties({EventosProperties.class, ComprasProperties.class})
public class IntegracionesHttpConfiguration {

    @Bean("eventosRestClient")
    RestClient eventosRestClient(RestClient.Builder builder, EventosProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean("comprasRestClient")
    RestClient comprasRestClient(RestClient.Builder builder, ComprasProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    Clock recommendationClock() {
        return Clock.systemUTC();
    }
}
