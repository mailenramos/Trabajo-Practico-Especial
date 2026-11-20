package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import ticketya.PruebaIntegracion;
import ticketya.modelo.AvisoPendiente;
import ticketya.modelo.Compra;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;
import ticketya.modelo.TipoAviso;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Entradas rechaza la emisión con 409 EVENTO_CERRADO: la compra cobrada se reintegra sola. */
class EventoCerradoAlEmitirTest extends PruebaIntegracion {

    private static final String CLIENTE = "cliente-1";
    /** 2 de platea (15000.00) y 2 de campo (8000.50). */
    private static final BigDecimal TOTAL = new BigDecimal("46001.00");

    private List<Reintegro> reintegrosPorCancelacion(UUID compraId) {
        return reintegros.findByCompraIdAndOrigen(compraId, OrigenReintegro.CANCELACION_EVENTO);
    }

    private List<AvisoPendiente> avisosDe(TipoAviso tipo) {
        return avisos.findByTipoOrderByIdAsc(tipo);
    }

    @Test
    void unPagoConfirmadoDespuesDelCierreNoEmiteYReintegraElTotalConElAviso() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);
        entradas.cerrarEvento(EVENTO_ID);

        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FALLIDA"))
                .andExpect(jsonPath("$.reintegros.length()").value(1))
                .andExpect(jsonPath("$.reintegros[0].origen").value("CANCELACION_EVENTO"))
                .andExpect(jsonPath("$.reintegros[0].estado").value("EXITOSO"));

        // Sin reintentos: un evento cerrado no se arregla repitiendo la emisión
        assertThat(entradas.llamadas()).isEqualTo(1);
        Compra compra = compras.buscarConDetalle(compraId).orElseThrow();
        assertThat(compra.getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        assertThat(compra.getDetalle()).extracting(DetalleCompra::getEntradaId).containsOnlyNulls();

        List<Reintegro> lista = reintegrosPorCancelacion(compraId);
        assertThat(lista).hasSize(1);
        assertThat(lista.get(0).getMonto()).isEqualByComparingTo(TOTAL);
        assertThat(lista.get(0).getMotivo()).isEqualTo("Evento cancelado");
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);

        List<AvisoPendiente> cancelados = avisosDe(TipoAviso.EVENTO_CANCELADO);
        assertThat(cancelados).hasSize(1);
        assertThat(cancelados.get(0).getUsuarioId()).isEqualTo(CLIENTE);
        assertThat(cancelados.get(0).getAcontecimientoId()).isEqualTo("evento-cancelado-" + EVENTO_ID);
        assertThat(cancelados.get(0).getRecursoId()).isEqualTo(EVENTO_ID);
        assertThat(avisosDe(TipoAviso.COMPRA_CONFIRMADA)).isEmpty();
        assertThat(avisosDe(TipoAviso.ENTRADAS_DISPONIBLES)).isEmpty();
    }

    @Test
    void repetirElCasoNoCreaOtroReintegroNiOtroAviso() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);
        entradas.cerrarEvento(EVENTO_ID);
        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD").andExpect(status().isOk());

        // El mismo pedido, otro pedido con otra clave y después el aviso de cancelación del evento
        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("FALLIDA"));
        pagar(CLIENTE, compraId, "clave-2", "CREDIT_CARD")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("FALLIDA"));
        mvc.perform(post("/eventos-cancelados").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventoId\":\"" + EVENTO_ID + "\",\"motivo\":\"Lluvia\"}"))
                .andExpect(status().isAccepted());

        assertThat(reintegrosPorCancelacion(compraId)).hasSize(1)
                .allMatch(r -> r.getEstado() == EstadoReintegro.EXITOSO);
        assertThat(reintegros.count()).isEqualTo(1);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
        assertThat(pagos.cobrosNuevos()).isEqualTo(1);
        assertThat(avisosDe(TipoAviso.EVENTO_CANCELADO)).hasSize(1);
    }

    @Test
    void unaCompraYaEmitidaAntesDelCierreRecibeLasMismasEntradasYSeConfirma() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);
        // Entradas emitió las entradas, pero la respuesta no llegó y después dejó de responder:
        // el pago queda a medias (202)
        entradas.perderProximasRespuestas(1);
        entradas.setCaido(true);
        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD").andExpect(status().isAccepted());
        assertThat(entradas.emisiones()).isEqualTo(1);

        // Después se cierra el evento y el pago se retoma con la misma clave
        entradas.setCaido(false);
        entradas.cerrarEvento(EVENTO_ID);
        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));

        Compra compra = compras.buscarConDetalle(compraId).orElseThrow();
        assertThat(compra.getDetalle()).extracting(DetalleCompra::getEntradaId).doesNotContainNull();
        assertThat(entradas.emisiones()).as("las mismas entradas, sin emitir otras").isEqualTo(1);
        assertThat(reintegros.count()).isZero();
        assertThat(avisosDe(TipoAviso.EVENTO_CANCELADO)).isEmpty();
        assertThat(avisosDe(TipoAviso.COMPRA_CONFIRMADA)).hasSize(1);
    }
}
