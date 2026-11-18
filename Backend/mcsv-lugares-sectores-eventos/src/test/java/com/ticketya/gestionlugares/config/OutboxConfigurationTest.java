package com.ticketya.gestionlugares.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

class OutboxConfigurationTest {

    @Test
    void configuraTimeoutDeConexionDeDosSegundosYLecturaDeCincoSegundos() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.clone()).thenReturn(builder);
        when(builder.requestFactory(org.mockito.ArgumentMatchers.any(ClientHttpRequestFactory.class)))
                .thenReturn(builder);
        when(builder.build()).thenReturn(mock(RestClient.class));

        new OutboxConfiguration().outboxRestClient(builder);

        ArgumentCaptor<ClientHttpRequestFactory> factoryCaptor =
                ArgumentCaptor.forClass(ClientHttpRequestFactory.class);
        verify(builder).requestFactory(factoryCaptor.capture());
        JdkClientHttpRequestFactory factory =
                assertInstanceOf(JdkClientHttpRequestFactory.class, factoryCaptor.getValue());

        HttpClient httpClient = (HttpClient) ReflectionTestUtils.getField(factory, "httpClient");
        assertEquals(Duration.ofSeconds(2), httpClient.connectTimeout().orElseThrow());
        assertEquals(Duration.ofSeconds(5), ReflectionTestUtils.getField(factory, "readTimeout"));
    }
}
