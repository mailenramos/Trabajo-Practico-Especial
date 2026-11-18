package com.ticketya.gestionlugares.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ticketya.gestionlugares.domain.AvisoOutbox;

class RestClientOutboxHttpClientTest {

    @Test
    void enviaPostConXAvisoIdEstableEnCadaReentregaYSinCambiarPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientOutboxHttpClient client = new RestClientOutboxHttpClient(builder.build());
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setId("cancelacion-evento-1");
        aviso.setDestino("http://compras.test/eventos-cancelados");
        aviso.setPayload(Map.of("eventoId", "evento-1", "motivo", "Cierre preventivo"));

        for (int intento = 0; intento < 2; intento++) {
            server.expect(requestTo("http://compras.test/eventos-cancelados"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("X-Aviso-Id", "cancelacion-evento-1"))
                    .andExpect(content().json("""
                            {"eventoId":"evento-1","motivo":"Cierre preventivo"}
                            """))
                    .andRespond(withStatus(HttpStatus.ACCEPTED));
        }

        for (int intento = 0; intento < 2; intento++) {
            assertEquals(202, client.enviar(aviso));
        }

        server.verify();
    }
}
