package com.ticketya.preferencias.integration.eventos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpEventosClientTest {

    @Test
    void obtieneTodasLasPaginasUsandoTamanioMaximo() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEventosClient client = new HttpEventosClient(
                builder.baseUrl("http://eventos.test").build());

        server.expect(requestTo("http://eventos.test/internal/eventos-publicados?page=0&size=200"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {
                          "contenido": [{
                            "eventoId": "evento-1",
                            "nombre": "Festival",
                            "tipo": "MUSICA",
                            "participantes": ["Artista"],
                            "ciudad": "Rosario",
                            "fechaHora": "2027-01-01T00:00:00Z",
                            "estado": "publicado"
                          }],
                          "pagina": 0,
                          "tamano": 200,
                          "totalElementos": 2,
                          "totalPaginas": 2
                        }
                        """, APPLICATION_JSON));
        server.expect(requestTo("http://eventos.test/internal/eventos-publicados?page=1&size=200"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {
                          "contenido": [{
                            "eventoId": "evento-2",
                            "nombre": "Teatro",
                            "tipo": "TEATRO",
                            "participantes": [],
                            "ciudad": "Córdoba",
                            "fechaHora": "2027-02-01T00:00:00Z",
                            "estado": "agotado"
                          }],
                          "pagina": 1,
                          "tamano": 200,
                          "totalElementos": 2,
                          "totalPaginas": 2
                        }
                        """, APPLICATION_JSON));

        List<EventoPublicado> eventos = client.listarTodos();

        assertEquals(List.of(
                new EventoPublicado("evento-1", "Festival", "MUSICA", List.of("Artista"),
                        "Rosario", Instant.parse("2027-01-01T00:00:00Z"), "publicado"),
                new EventoPublicado("evento-2", "Teatro", "TEATRO", List.of(),
                        "Córdoba", Instant.parse("2027-02-01T00:00:00Z"), "agotado")),
                eventos);
        server.verify();
    }

    @Test
    void errorDeMs1SeConvierteEnExcepcionDeCatalogoNoDisponible() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEventosClient client = new HttpEventosClient(
                builder.baseUrl("http://eventos.test").build());
        server.expect(requestTo("http://eventos.test/internal/eventos-publicados?page=0&size=200"))
                .andExpect(method(GET))
                .andRespond(withServerError());

        assertThrows(EventosCatalogoUnavailableException.class, client::listarTodos);
        server.verify();
    }
}
