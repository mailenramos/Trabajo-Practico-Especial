package com.ticketya.preferencias.integration.notificaciones;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.ACCEPTED;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ticketya.preferencias.integration.compras.ComprasProperties;

class HttpNotificacionesClientTest {
    @Test
    void enviaPayloadContratoYTokenYAceptaSolo202() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NotificacionesProperties notificacionesProperties = new NotificacionesProperties();
        notificacionesProperties.setBaseUrl("http://notificaciones.test");
        ComprasProperties comprasProperties = new ComprasProperties();
        comprasProperties.setServicioToken("token-secreto");
        HttpNotificacionesClient client = new HttpNotificacionesClient(
                builder.baseUrl(notificacionesProperties.getBaseUrl()).build(), comprasProperties);

        server.expect(requestTo("http://notificaciones.test/internal/notificaciones"))
                .andExpect(method(POST))
                .andExpect(header("X-Service-Token", "token-secreto"))
                .andExpect(content().contentType(APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "acontecimientoId":"publicacion-evento-1-cliente-1",
                          "tipo":"NUEVO_EVENTO_DE_INTERES",
                          "versionEsquema":1,
                          "ocurridoEn":"2026-11-19T16:00:00Z",
                          "recursoId":"evento-1",
                          "usuarioId":"cliente-1",
                          "correlacionId":"correlacion-1",
                          "datos":{
                            "evento":"Concierto",
                            "fecha":"02/01/2030 00:04",
                            "lugar":"Rosario"
                          }
                        }
                        """))
                .andRespond(withStatus(ACCEPTED));

        client.enviar(new NotificacionRequest(
                "publicacion-evento-1-cliente-1",
                "NUEVO_EVENTO_DE_INTERES",
                1,
                Instant.parse("2026-11-19T16:00:00Z"),
                "evento-1",
                "cliente-1",
                "correlacion-1",
                new NotificacionRequest.Datos("Concierto", "02/01/2030 00:04", "Rosario")));
        server.verify();
    }

    @Test
    void respuestaDiferenteDe202EsFalloDeEntrega() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ComprasProperties comprasProperties = new ComprasProperties();
        comprasProperties.setServicioToken("token");
        HttpNotificacionesClient client = new HttpNotificacionesClient(
                builder.baseUrl("http://notificaciones.test").build(), comprasProperties);
        server.expect(requestTo("http://notificaciones.test/internal/notificaciones"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.OK));

        assertThrows(NotificacionesUnavailableException.class,
                () -> client.enviar(new NotificacionRequest("id", "tipo", 1, Instant.now(),
                        "evento", "usuario", "correlacion", new NotificacionRequest.Datos("e", "f", "l"))));
        server.verify();
    }

    @Test
    void respuesta500EsFalloDeEntrega() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ComprasProperties comprasProperties = new ComprasProperties();
        comprasProperties.setServicioToken("token");
        HttpNotificacionesClient client = new HttpNotificacionesClient(
                builder.baseUrl("http://notificaciones.test").build(), comprasProperties);
        server.expect(requestTo("http://notificaciones.test/internal/notificaciones"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(NotificacionesUnavailableException.class,
                () -> client.enviar(new NotificacionRequest("id", "tipo", 1, Instant.now(),
                        "evento", "usuario", "correlacion", new NotificacionRequest.Datos("e", "f", "l"))));
        server.verify();
    }
}
