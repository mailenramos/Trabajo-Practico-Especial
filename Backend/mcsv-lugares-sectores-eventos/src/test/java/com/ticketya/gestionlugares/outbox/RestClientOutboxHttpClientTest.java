package com.ticketya.gestionlugares.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.mock.env.MockEnvironment;

import com.ticketya.gestionlugares.config.ServicioToken;
import com.ticketya.gestionlugares.domain.AvisoOutbox;

class RestClientOutboxHttpClientTest {

    @ParameterizedTest
    @ValueSource(strings = {"PUBLICACION", "CANCELACION"})
    void enviaPostConXAvisoIdEstableEnCadaReentregaYSinCambiarPayload(String tipo) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientOutboxHttpClient client = new RestClientOutboxHttpClient(builder.build(),
                new ServicioToken("token-prueba", new MockEnvironment()));
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setTipo(tipo);
        aviso.setId("aviso-evento-1");
        aviso.setDestino("http://destino.test/avisos");
        aviso.setPayload(Map.of("eventoId", "evento-1", "detalle", "notificacion"));

        for (int intento = 0; intento < 2; intento++) {
            server.expect(requestTo("http://destino.test/avisos"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("X-Aviso-Id", "aviso-evento-1"))
                    .andExpect(header("X-Service-Token", "token-prueba"))
                    .andExpect(content().json("""
                            {"eventoId":"evento-1","detalle":"notificacion"}
                            """))
                    .andRespond(withStatus(HttpStatus.ACCEPTED));
        }

        for (int intento = 0; intento < 2; intento++) {
            assertEquals(202, client.enviar(aviso));
        }

        server.verify();
    }

    @Test
    void sinTokenNoEnviaSolicitud() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientOutboxHttpClient client = new RestClientOutboxHttpClient(builder.build(),
                new ServicioToken("", new MockEnvironment()));
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setDestino("http://destino.test/avisos");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> client.enviar(aviso));
        server.verify();
    }

    @Test
    void excepcionDeTransporteNoExponeToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientOutboxHttpClient client = new RestClientOutboxHttpClient(builder.build(),
                new ServicioToken("token-prueba", new MockEnvironment()));
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setId("aviso-1");
        aviso.setDestino("http://destino.test/avisos");
        aviso.setPayload(Map.of("eventoId", "evento-1"));
        server.expect(requestTo("http://destino.test/avisos"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withException(new IOException("token-prueba")));

        ResourceAccessException error = org.junit.jupiter.api.Assertions.assertThrows(
                ResourceAccessException.class, () -> client.enviar(aviso));
        org.assertj.core.api.Assertions.assertThat(error.toString()).doesNotContain("token-prueba");
        org.junit.jupiter.api.Assertions.assertNull(error.getCause());
        server.verify();
    }
}
