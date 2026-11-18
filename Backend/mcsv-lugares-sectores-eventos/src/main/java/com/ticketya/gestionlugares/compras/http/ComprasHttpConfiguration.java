package com.ticketya.gestionlugares.compras.http;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ComprasProperties.class)
public class ComprasHttpConfiguration {

    @Bean
    RestClient comprasRestClient(RestClient.Builder builder, ComprasProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.clone().baseUrl(properties.getBaseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
