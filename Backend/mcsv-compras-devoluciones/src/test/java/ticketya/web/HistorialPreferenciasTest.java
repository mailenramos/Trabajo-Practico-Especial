package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.PruebaIntegracion;
import ticketya.modelo.Compra;
import ticketya.modelo.EstadoCompra;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /internal/usuarios/{usuarioId}/historial-preferencias. */
class HistorialPreferenciasTest extends PruebaIntegracion {

    private static final String RUTA = "/internal/usuarios/{id}/historial-preferencias";

    private void compra(String clienteId, String eventoId, EstadoCompra estado, String tipo, Instant fecha) {
        Compra c = new Compra();
        c.setClienteId(clienteId);
        c.setReservaId(UUID.randomUUID().toString());
        c.setEventoId(eventoId);
        c.setEventoNombre("Evento " + eventoId);
        c.setEventoFechaInicio(Instant.parse("2026-12-01T21:00:00Z"));
        c.setTotal(new BigDecimal("1000.00"));
        c.setEstado(estado);
        c.setTipoEvento(tipo);
        c.setFecha(fecha);
        compras.saveAndFlush(c);
    }

    private void confirmada(String clienteId, String eventoId, String tipo) {
        compra(clienteId, eventoId, EstadoCompra.CONFIRMADA, tipo, Instant.now());
    }

    @Test
    void devuelveLosTiposDeMayorAMenor() throws Exception {
        confirmada("c1", "A", "RECITAL");
        confirmada("c1", "B", "RECITAL");
        confirmada("c1", "C", "RECITAL");
        confirmada("c1", "D", "TEATRO");
        compra("c1", "E", EstadoCompra.PENDIENTE, "DEPORTE", Instant.now());

        consultarInterno(RUTA, "c1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuarioId").value("c1"))
                .andExpect(jsonPath("$.tipos", hasSize(2)))
                .andExpect(jsonPath("$.tipos[0].tipo").value("RECITAL"))
                .andExpect(jsonPath("$.tipos[0].cantidad").value(3))
                .andExpect(jsonPath("$.tipos[1].tipo").value("TEATRO"))
                .andExpect(jsonPath("$.tipos[1].cantidad").value(1))
                .andExpect(jsonPath("$.eventosComprados", hasSize(4)));
    }

    @Test
    void eventosCompradosSinRepetirDelMasRecienteYConLasComprasSinTipo() throws Exception {
        Instant base = Instant.parse("2026-11-01T12:00:00Z");
        compra("c1", "A", EstadoCompra.CONFIRMADA, "RECITAL", base);
        compra("c1", "B", EstadoCompra.CONFIRMADA, null, base.plusSeconds(60));
        compra("c1", "A", EstadoCompra.CONFIRMADA, "RECITAL", base.plusSeconds(120));

        consultarInterno(RUTA, "c1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipos", hasSize(1)))
                .andExpect(jsonPath("$.tipos[0].tipo").value("RECITAL"))
                .andExpect(jsonPath("$.tipos[0].cantidad").value(2))
                .andExpect(jsonPath("$.eventosComprados", hasSize(2)))
                .andExpect(jsonPath("$.eventosComprados[0]").value("A"))
                .andExpect(jsonPath("$.eventosComprados[1]").value("B"));
    }

    @Test
    void eventosCompradosDevuelveComoMaximo20() throws Exception {
        Instant base = Instant.parse("2026-11-01T12:00:00Z");
        for (int i = 0; i < 22; i++) {
            compra("c1", "evento-" + i, EstadoCompra.CONFIRMADA, "RECITAL", base.plusSeconds(i));
        }

        consultarInterno(RUTA, "c1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventosComprados", hasSize(20)))
                .andExpect(jsonPath("$.eventosComprados[0]").value("evento-21"));
    }

    @Test
    void unClienteSinComprasConfirmadasRespondeListasVaciasY200() throws Exception {
        compra("c1", "A", EstadoCompra.FALLIDA, "RECITAL", Instant.now());

        consultarInterno(RUTA, "c1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuarioId").value("c1"))
                .andExpect(jsonPath("$.tipos", hasSize(0)))
                .andExpect(jsonPath("$.eventosComprados", hasSize(0)));
    }

    @Test
    void sinTokenResponde403() throws Exception {
        mvc.perform(get(RUTA, "c1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SERVICIO_NO_AUTORIZADO"));
    }
}
