package com.ticketya.gestionlugares.compras.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ticketya.gestionlugares.compras.ComprasClientException;

class HttpComprasClientTest {

    @Test
    void mandaAvisoJsonYConsidera202Y200ComoExito() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://compras.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpComprasClient client = new HttpComprasClient(builder.build());
        for (int status : new int[] {202, 200}) {
            server.expect(requestTo("http://compras.test/eventos-cancelados"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().json("""
                            {"eventoId":"evento-1","motivo":"Cierre preventivo","canceladoEn":"2026-11-06T18:00:00Z"}
                            """))
                    .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(status)));
        }
        for (int ignored : new int[] {202, 200}) {
            assertDoesNotThrow(() -> client.notificarCancelacion("evento-1", "Cierre preventivo",
                    Instant.parse("2026-11-06T18:00:00Z")));
        }
        server.verify();
    }

    @Test
    void traduceErroresRemotosParaDejarElAvisoPendiente() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://compras.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpComprasClient client = new HttpComprasClient(builder.build());
        server.expect(requestTo("http://compras.test/eventos-cancelados"))
                .andRespond(withStatus(INTERNAL_SERVER_ERROR));

        assertThrows(ComprasClientException.class,
                () -> client.notificarCancelacion("evento-1", "motivo", Instant.now()));
        server.verify();
    }

    @Test
    void rechazaRespuestasQueNoSon200Ni202() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://compras.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpComprasClient client = new HttpComprasClient(builder.build());
        server.expect(requestTo("http://compras.test/eventos-cancelados"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CREATED));

        assertThrows(ComprasClientException.class,
                () -> client.notificarCancelacion("evento-1", "motivo", Instant.now()));
        server.verify();
    }
}
