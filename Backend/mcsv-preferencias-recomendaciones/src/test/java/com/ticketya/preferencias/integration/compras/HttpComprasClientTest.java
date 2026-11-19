package com.ticketya.preferencias.integration.compras;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpComprasClientTest {
    private static final String TOKEN = "token-de-prueba";

    @Test
    void enviaTokenEnHistorialYHastaCincoCoComprasEnOrden() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpComprasClient client = client(builder.baseUrl("http://compras.test").build());

        server.expect(requestTo("http://compras.test/internal/usuarios/cliente-1/historial-preferencias"))
                .andExpect(method(GET))
                .andExpect(header("X-Service-Token", TOKEN))
                .andRespond(withSuccess("""
                        {
                          "usuarioId": "cliente-1",
                          "tipos": [{"tipo":"MUSICA","cantidad":4}],
                          "eventosComprados": ["compra-1"," compra-1 ","compra-2","compra-3","compra-4","compra-5","compra-6"]
                        }
                        """, APPLICATION_JSON));

        for (int i = 1; i <= 5; i++) {
            String eventoId = "compra-" + i;
            server.expect(requestTo("http://compras.test/internal/eventos/" + eventoId + "/co-compras"))
                    .andExpect(method(GET))
                    .andExpect(header("X-Service-Token", TOKEN))
                    .andRespond(withSuccess("""
                            {
                              "eventoId": "%s",
                              "coCompras": [{"eventoId":"recomendado-%d","cantidad":2}]
                            }
                            """.formatted(eventoId, i), APPLICATION_JSON));
        }

        SenalesCompras senales = client.obtenerSenales("cliente-1");

        assertEquals(Set.of("MUSICA"), senales.tiposComprados());
        assertEquals(Set.of("recomendado-1", "recomendado-2", "recomendado-3",
                "recomendado-4", "recomendado-5"), senales.eventosCoComprados());
        server.verify();
    }

    @Test
    void eventosCompradosOmitidoSeTrataComoListaVacia() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpComprasClient client = client(builder.baseUrl("http://compras.test").build());
        server.expect(requestTo("http://compras.test/internal/usuarios/cliente-1/historial-preferencias"))
                .andExpect(header("X-Service-Token", TOKEN))
                .andRespond(withSuccess("""
                        {"usuarioId":"cliente-1","tipos":[]}
                        """, APPLICATION_JSON));

        SenalesCompras senales = client.obtenerSenales("cliente-1");

        assertEquals(Set.of(), senales.tiposComprados());
        assertEquals(Set.of(), senales.eventosCoComprados());
        server.verify();
    }

    @Test
    void errorEnUnaConsultaDeCoComprasFallaElAgregadoCompleto() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpComprasClient client = client(builder.baseUrl("http://compras.test").build());
        server.expect(requestTo("http://compras.test/internal/usuarios/cliente-1/historial-preferencias"))
                .andExpect(header("X-Service-Token", TOKEN))
                .andRespond(withSuccess("""
                        {"usuarioId":"cliente-1","tipos":[{"tipo":"MUSICA","cantidad":1}],
                         "eventosComprados":["compra-1"]}
                        """, APPLICATION_JSON));
        server.expect(requestTo("http://compras.test/internal/eventos/compra-1/co-compras"))
                .andExpect(header("X-Service-Token", TOKEN))
                .andRespond(withServerError());

        assertThrows(ComprasUnavailableException.class, () -> client.obtenerSenales("cliente-1"));
        server.verify();
    }

    @Test
    void errorDeHistorialSeReportaComoComprasNoDisponible() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpComprasClient client = client(builder.baseUrl("http://compras.test").build());
        server.expect(requestTo("http://compras.test/internal/usuarios/cliente-1/historial-preferencias"))
                .andRespond(withServerError());

        assertThrows(ComprasUnavailableException.class, () -> client.obtenerSenales("cliente-1"));
        server.verify();
    }

    private HttpComprasClient client(RestClient restClient) {
        ComprasProperties properties = new ComprasProperties();
        properties.setServicioToken(TOKEN);
        return new HttpComprasClient(restClient, properties);
    }
}
