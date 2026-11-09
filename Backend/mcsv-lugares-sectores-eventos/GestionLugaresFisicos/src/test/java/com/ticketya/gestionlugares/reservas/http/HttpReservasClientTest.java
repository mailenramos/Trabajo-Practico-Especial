package com.ticketya.gestionlugares.reservas.http;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.reservas.EstadoVentaReservas;
import com.ticketya.gestionlugares.reservas.ReservasClientException;
import com.ticketya.gestionlugares.reservas.dto.AsientoReservas;
import com.ticketya.gestionlugares.reservas.dto.InicializarDisponibilidadRequest;
import com.ticketya.gestionlugares.reservas.dto.SectorDisponibilidadRequest;
import com.ticketya.gestionlugares.reservas.dto.DisponibilidadEventoResponse;
import com.ticketya.gestionlugares.reservas.dto.SectorDisponibilidadResponse;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.CONFLICT;

class HttpReservasClientTest {

    @Test
    void inicializaConElContratoJsonYAcepta204() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://reservas.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpReservasClient client = new HttpReservasClient(builder.build());
        server.expect(requestTo("http://reservas.test/disponibilidad/eventos/evento-1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"limiteEntradasPorCliente":4,"sectores":[
                          {"sectorId":"numerado-1","tipo":"NUMERADO","capacidadTotal":2,
                           "asientos":[{"fila":1,"numero":1},{"fila":1,"numero":2}]},
                          {"sectorId":"general-1","tipo":"NO_NUMERADO","capacidadTotal":500,"asientos":[]}
                        ]}
                        """))
                .andRespond(withNoContent());

        client.inicializarDisponibilidad("evento-1", new InicializarDisponibilidadRequest(4, List.of(
                new SectorDisponibilidadRequest("numerado-1", TipoSector.NUMERADO, 2,
                        List.of(new AsientoReservas(1, 1), new AsientoReservas(1, 2))),
                new SectorDisponibilidadRequest("general-1", TipoSector.NO_NUMERADO, 500, List.of()))));

        server.verify();
    }

    @Test
    void enviaEstadoDeVentaYTraduceRespuesta409() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://reservas.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpReservasClient client = new HttpReservasClient(builder.build());
        server.expect(requestTo("http://reservas.test/disponibilidad/eventos/evento-1/venta"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"estado\":\"SUSPENDIDA\"}"))
                .andRespond(withStatus(CONFLICT));

        assertThrows(ReservasClientException.class,
                () -> client.cambiarEstadoVenta("evento-1", EstadoVentaReservas.SUSPENDIDA));
        server.verify();
    }

    @Test
    void acepta204AlRepetirElMismoEstadoDeVenta() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://reservas.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpReservasClient client = new HttpReservasClient(builder.build());
        for (int intento = 0; intento < 2; intento++) {
            server.expect(requestTo("http://reservas.test/disponibilidad/eventos/evento-1/venta"))
                    .andExpect(method(HttpMethod.PUT))
                    .andExpect(content().json("{\"estado\":\"ACTIVA\"}"))
                    .andRespond(withNoContent());
        }
        for (int intento = 0; intento < 2; intento++) {
            client.cambiarEstadoVenta("evento-1", EstadoVentaReservas.ACTIVA);
        }
        server.verify();
    }

    @Test
    void soloConsideraExitosoEl204AlInicializar() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://reservas.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpReservasClient client = new HttpReservasClient(builder.build());
        server.expect(requestTo("http://reservas.test/disponibilidad/eventos/evento-1"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());
        assertThrows(ReservasClientException.class,
                () -> client.inicializarDisponibilidad("evento-1", new InicializarDisponibilidadRequest(4, List.of())));
        server.verify();
    }

    @Test
    void consultaDisponibilidadEnLaRutaAcordadaYDeserializaSectoresYAsientos() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://reservas.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpReservasClient client = new HttpReservasClient(builder.build());
        server.expect(requestTo("http://reservas.test/disponibilidad/eventos/evento-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"eventoId":"evento-1","sectores":[
                          {"sectorId":"sector-1","tipo":"NUMERADO","capacidadTotal":2,"cantidadDisponible":1,
                           "asientosDisponibles":[{"fila":1,"numero":2}]},
                          {"sectorId":"sector-2","tipo":"NO_NUMERADO","capacidadTotal":50,"cantidadDisponible":17}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        DisponibilidadEventoResponse respuesta = client.consultarDisponibilidad("evento-1");

        org.junit.jupiter.api.Assertions.assertEquals("evento-1", respuesta.eventoId());
        org.junit.jupiter.api.Assertions.assertEquals(2, respuesta.sectores().size());
        org.junit.jupiter.api.Assertions.assertEquals(1, respuesta.sectores().getFirst().cantidadDisponible());
        org.junit.jupiter.api.Assertions.assertEquals(2,
                respuesta.sectores().getFirst().asientosDisponibles().getFirst().numero());
        server.verify();
    }

    @Test
    void convierte404Y500DeConsultaEnErrorDelCliente() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://reservas.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpReservasClient client = new HttpReservasClient(builder.build());
        server.expect(requestTo("http://reservas.test/disponibilidad/eventos/evento-1"))
                .andExpect(method(HttpMethod.GET)).andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://reservas.test/disponibilidad/eventos/evento-1"))
                .andExpect(method(HttpMethod.GET)).andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(ReservasClientException.class, () -> client.consultarDisponibilidad("evento-1"));
        assertThrows(ReservasClientException.class, () -> client.consultarDisponibilidad("evento-1"));
        server.verify();
    }
}
