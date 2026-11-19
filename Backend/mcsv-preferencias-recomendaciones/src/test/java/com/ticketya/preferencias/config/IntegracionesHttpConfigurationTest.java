package com.ticketya.preferencias.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

import com.ticketya.preferencias.integration.compras.ComprasProperties;
import com.ticketya.preferencias.integration.eventos.EventosProperties;
import com.ticketya.preferencias.integration.notificaciones.NotificacionesProperties;

class IntegracionesHttpConfigurationTest {

    @Test
    void configuraUrlsTimeoutsYTokenSinDefaultUtil() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(IntegracionesHttpConfiguration.class)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withPropertyValues("SERVICIO_TOKEN=")
                .run(context -> {
                    EventosProperties eventos = context.getBean(EventosProperties.class);
                    ComprasProperties compras = context.getBean(ComprasProperties.class);
                    NotificacionesProperties notificaciones = context.getBean(NotificacionesProperties.class);

                    assertEquals("http://localhost:8081", eventos.getBaseUrl());
                    assertEquals(Duration.ofSeconds(2), eventos.getConnectTimeout());
                    assertEquals(Duration.ofSeconds(5), eventos.getReadTimeout());
                    assertEquals("http://localhost:8083", compras.getBaseUrl());
                    assertEquals("", compras.getServicioToken());
                    assertEquals(Duration.ofSeconds(2), compras.getConnectTimeout());
                    assertEquals(Duration.ofSeconds(5), compras.getReadTimeout());
                    assertEquals("http://localhost:8090", notificaciones.getBaseUrl());
                    assertEquals(Duration.ofSeconds(2), notificaciones.getConnectTimeout());
                    assertEquals(Duration.ofSeconds(5), notificaciones.getReadTimeout());
                });
    }
}
