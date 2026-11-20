package ticketya.cliente.rest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ticketya.cliente.EventosCliente.Evento;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Lectura del evento de Eventos, incluido su tipo (Entrega 2). */
class EventosClienteRestTest {

    private ServidorDePrueba servidor;
    private EventosClienteRest cliente;

    @BeforeEach
    void levantar() throws Exception {
        servidor = new ServidorDePrueba();
        cliente = new EventosClienteRest(servidor.url(), Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @AfterEach
    void apagar() {
        servidor.close();
    }

    @Test
    void leeElTipoDelEvento() {
        servidor.responder(200, """
                {"eventoId":"evento-1","nombre":"Recital","fechaInicio":"2026-12-01T21:00:00Z",
                 "estado":"publicado","tipo":"RECITAL",
                 "sectores":[{"sectorId":"platea","nombre":"Platea","precio":15000.00}]}""");

        Evento evento = cliente.obtenerEvento("evento-1").orElseThrow();

        assertThat(servidor.ruta).isEqualTo("/internal/eventos/evento-1");
        assertThat(evento.tipo()).isEqualTo("RECITAL");
        assertThat(evento.nombre()).isEqualTo("Recital");
        assertThat(evento.fechaInicio()).isEqualTo(Instant.parse("2026-12-01T21:00:00Z"));
        assertThat(evento.sectores()).hasSize(1);
    }

    @Test
    void sinTipoElEventoSeLeeIgualConTipoNulo() {
        servidor.responder(200, """
                {"eventoId":"evento-1","nombre":"Recital","fechaInicio":"2026-12-01T21:00:00Z",
                 "estado":"publicado","sectores":[]}""");

        assertThat(cliente.obtenerEvento("evento-1").orElseThrow().tipo()).isNull();
    }
}
