package com.ticketya.msvc.entradas.clientes;

import com.ticketya.msvc.entradas.config.EntradasProperties;
import com.ticketya.msvc.entradas.exception.ServicioNoDisponibleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AutorizacionAccesoClienteRestTest {

    private static final String URL =
            "http://ms1/internal/eventos/evento-1/personal-acceso/usuario-1/autorizacion";

    private MockRestServiceServer ms1;
    private RestClient.Builder builder;

    @BeforeEach
    void preparar() {
        builder = RestClient.builder().baseUrl("http://ms1");
        ms1 = MockRestServiceServer.bindTo(builder).build();
    }

    private AutorizacionAccesoClienteRest cliente(String token) {
        return new AutorizacionAccesoClienteRest(builder.build(),
                new EntradasProperties(new EntradasProperties.Seguridad(token), null));
    }

    @Test
    void autorizadoTrueYFalseSeRespetan() {
        AutorizacionAccesoClienteRest cliente = cliente("secreto");
        ms1.expect(requestTo(URL))
                .andExpect(header("X-Service-Token", "secreto"))
                .andExpect(request -> assertTrue(request.getHeaders().containsKey("X-Correlation-Id")))
                .andRespond(withSuccess("{\"eventoId\":\"evento-1\",\"usuarioId\":\"usuario-1\",\"autorizado\":true}",
                        MediaType.APPLICATION_JSON));
        ms1.expect(requestTo(URL))
                .andRespond(withSuccess("{\"eventoId\":\"evento-1\",\"usuarioId\":\"usuario-1\",\"autorizado\":false}",
                        MediaType.APPLICATION_JSON));

        assertTrue(cliente.estaAutorizado("evento-1", "usuario-1"));
        assertFalse(cliente.estaAutorizado("evento-1", "usuario-1"));
        ms1.verify();
    }

    @Test
    void sinTokenConfiguradoNoEnviaElEncabezado() {
        ms1.expect(requestTo(URL))
                .andExpect(headerDoesNotExist("X-Service-Token"))
                .andRespond(withSuccess("{\"autorizado\":true}", MediaType.APPLICATION_JSON));

        assertTrue(cliente("").estaAutorizado("evento-1", "usuario-1"));
    }

    @Test
    void errorDeMs1_seConvierteEnServicioNoDisponible() {
        AutorizacionAccesoClienteRest cliente = cliente(null);
        for (HttpStatus estado : new HttpStatus[] {HttpStatus.NOT_FOUND, HttpStatus.INTERNAL_SERVER_ERROR}) {
            ms1.reset();
            ms1.expect(requestTo(URL)).andRespond(withStatus(estado));

            ServicioNoDisponibleException ex = assertThrows(ServicioNoDisponibleException.class,
                    () -> cliente.estaAutorizado("evento-1", "usuario-1"));
            assertEquals("AUTORIZACION_NO_DISPONIBLE", ex.getCodigo());
        }
    }

    @Test
    void respuestaSinCampoAutorizado_seConvierteEnServicioNoDisponible() {
        ms1.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThrows(ServicioNoDisponibleException.class,
                () -> cliente(null).estaAutorizado("evento-1", "usuario-1"));
    }
}
