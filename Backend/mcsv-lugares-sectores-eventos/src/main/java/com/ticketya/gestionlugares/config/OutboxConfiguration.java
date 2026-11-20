package com.ticketya.gestionlugares.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.ticketya.gestionlugares.compras.http.ComprasProperties;
import com.ticketya.gestionlugares.filas.http.FilasProperties;
import com.ticketya.gestionlugares.preferencias.http.PreferenciasProperties;
import com.ticketya.gestionlugares.reservas.http.ReservasProperties;

@Configuration
@EnableConfigurationProperties({ComprasProperties.class, PreferenciasProperties.class, FilasProperties.class,
        ReservasProperties.class})
public class OutboxConfiguration {

    @Bean
    Clock outboxClock() {
        return Clock.systemUTC();
    }

    @Bean
    @Qualifier("outboxRestClient")
    RestClient outboxRestClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder.clone().requestFactory(requestFactory).build();
    }

    @Bean
    @Qualifier("outboxReservasRestClient")
    RestClient outboxReservasRestClient(RestClient.Builder builder, ReservasProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.clone().requestFactory(requestFactory).build();
    }
}
