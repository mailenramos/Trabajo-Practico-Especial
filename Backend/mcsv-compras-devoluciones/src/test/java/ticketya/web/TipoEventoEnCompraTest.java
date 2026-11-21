package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.PruebaIntegracion;
import ticketya.cliente.EventosCliente.Evento;
import ticketya.cliente.EventosCliente.Sector;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La compra guarda el tipo de evento que devuelve Eventos al iniciarla. */
class TipoEventoEnCompraTest extends PruebaIntegracion {

    private void eventoConTipo(String tipo) {
        eventos.agregar(new Evento(EVENTO_ID, "Recital de prueba", Instant.parse("2026-12-01T21:00:00Z"),
                "publicado", List.of(
                new Sector(PLATEA, "Platea", new BigDecimal("15000.00")),
                new Sector(CAMPO, "Campo", new BigDecimal("8000.50"))), tipo));
    }

    private String tipoDeLaCompraIniciada() throws Exception {
        String reservaId = reservaVigente("cliente-1");
        iniciarCompra("cliente-1", reservaId).andExpect(status().isCreated());
        return compras.findByReservaId(reservaId).orElseThrow().getTipoEvento();
    }

    @Test
    void laCompraNuevaGuardaElTipoDelEvento() throws Exception {
        eventoConTipo("RECITAL");

        assertThat(tipoDeLaCompraIniciada()).isEqualTo("RECITAL");
    }

    @Test
    void siEventosNoDevuelveTipoLaCompraSeCreaIgualConTipoNulo() throws Exception {
        // El evento de la base de las pruebas no tiene tipo
        assertThat(tipoDeLaCompraIniciada()).isNull();
    }

    @Test
    void unTipoVacioQuedaNulo() throws Exception {
        eventoConTipo("  ");

        assertThat(tipoDeLaCompraIniciada()).isNull();
    }
}
