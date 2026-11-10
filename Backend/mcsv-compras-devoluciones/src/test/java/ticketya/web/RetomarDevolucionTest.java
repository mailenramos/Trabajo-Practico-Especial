package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.cliente.EntradasCliente.EstadoEntrada;
import ticketya.cliente.PagosCliente.EstadoReintegroPagos;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.EstadoSolicitud;
import ticketya.modelo.SolicitudDevolucion;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Una aprobación incompleta se retoma sin revertir y sin repetir efectos. */
class RetomarDevolucionTest extends PruebaDevoluciones {

    private UUID reintegroDe(UUID devolucionId) {
        return reintegros.findByRefundRequestId(devolucionId).orElseThrow().getId();
    }

    @Test
    void siEntradasNoRespondeLaAprobacionQuedaIncompletaSinLiberarNiReintegrar() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));
        entradas.setCaido(true);

        aprobar(ADMIN, "ADMIN", id)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.coordinacion.completa").value(false))
                .andExpect(jsonPath("$.coordinacion.pasoPendiente").value("ENTRADA_EN_REINTEGRO"))
                .andExpect(jsonPath("$.coordinacion.ultimoError").value(org.hamcrest.Matchers.containsString("ENTRADA_EN_REINTEGRO")))
                .andExpect(jsonPath("$.reintegro.estado").value("PENDIENTE"));

        assertThat(reservas.pedidosDeLiberacionPorDevolucion()).isZero();
        assertThat(pagos.pedidosDeReintegro()).isZero();
        assertThat(solicitudes.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoSolicitud.APROBADA);
    }

    @Test
    void unaTransicionInvalidaAEnReintegroQuedaRegistradaComoConflictoDeEstado() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        UUID id = devolucionPendiente(CLIENTE, entradaId);
        entradas.rechazarTransicion(entradaId);

        aprobar(ADMIN, "ADMIN", id)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.coordinacion.completa").value(false))
                .andExpect(jsonPath("$.coordinacion.pasoPendiente").value("ENTRADA_EN_REINTEGRO"));

        String error = solicitudes.findById(id).orElseThrow().getUltimoError();
        assertThat(error).startsWith("ENTRADA_EN_REINTEGRO")
                .contains("Conflicto de estado").contains("TRANSICION_INVALIDA")
                .doesNotContain("no responde");
        assertThat(reservas.pedidosDeLiberacionPorDevolucion()).isZero();
        assertThat(pagos.pedidosDeReintegro()).isZero();

        // Entradas acepta el cambio: el reintento completa la coordinación sin duplicar
        entradas.aceptarTransicion(entradaId);
        reintentarReintegro(reintegroDe(id)).andExpect(status().isOk());

        SolicitudDevolucion s = solicitudes.findById(id).orElseThrow();
        assertThat(s.coordinacionCompleta()).isTrue();
        assertThat(s.getUltimoError()).isNull();
        assertThat(reservas.pedidosDeLiberacionPorDevolucion()).isEqualTo(1);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
        assertThat(entradas.estado(entradaId)).isEqualTo(EstadoEntrada.DEVUELTA);
    }

    @Test
    void unaTransicionInvalidaADevueltaDejaElReintegroExitosoYElPasoPendiente() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        UUID id = devolucionPendiente(CLIENTE, entradaId);
        // La entrada ya está en reintegro (el paso a en reintegro se saltea) y Entradas
        // rechaza pasarla a devuelta
        entradas.agregar(entradas.entrada(entradaId).orElseThrow().conEstado(EstadoEntrada.EN_REINTEGRO));
        entradas.rechazarTransicion(entradaId);

        aprobar(ADMIN, "ADMIN", id)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.coordinacion.completa").value(false))
                .andExpect(jsonPath("$.coordinacion.pasoPendiente").value("ENTRADA_DEVUELTA"))
                .andExpect(jsonPath("$.reintegro.estado").value("EXITOSO"));

        SolicitudDevolucion s = solicitudes.findById(id).orElseThrow();
        assertThat(s.getUltimoError()).startsWith("ENTRADA_DEVUELTA")
                .contains("Conflicto de estado").doesNotContain("no responde");
        assertThat(reintegros.findByRefundRequestId(id).orElseThrow().getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(entradas.estado(entradaId)).isEqualTo(EstadoEntrada.EN_REINTEGRO);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
    }

    @Test
    void siReservasNoRespondeLaEntradaQuedaEnReintegroYNoSePideElReintegro() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        UUID id = devolucionPendiente(CLIENTE, entradaId);
        reservas.setCaido(true);

        aprobar(ADMIN, "ADMIN", id)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.coordinacion.pasoPendiente").value("LIBERAR_LUGAR"));

        assertThat(entradas.estado(entradaId)).isEqualTo(EstadoEntrada.EN_REINTEGRO);
        assertThat(pagos.pedidosDeReintegro()).isZero();
    }

    @Test
    void retomarContinuaDesdeElPasoQueFalloSinRepetirLosHechos() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        UUID id = devolucionPendiente(CLIENTE, entradaId);
        reservas.setCaido(true);
        aprobar(ADMIN, "ADMIN", id).andExpect(status().isAccepted());
        reservas.setCaido(false);

        reintentarReintegro(reintegroDe(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EXITOSO"));

        SolicitudDevolucion s = solicitudes.findById(id).orElseThrow();
        assertThat(s.coordinacionCompleta()).isTrue();
        assertThat(s.getUltimoError()).isNull();
        assertThat(entradas.pasesAEnReintegro()).isEqualTo(1);
        assertThat(reservas.pedidosDeLiberacionPorDevolucion()).isEqualTo(1);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
        assertThat(entradas.estado(entradaId)).isEqualTo(EstadoEntrada.DEVUELTA);
    }

    @Test
    void unPasoQueYaSeHabiaAplicadoEnEntradasNoSeRepite() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        UUID id = devolucionPendiente(CLIENTE, entradaId);
        entradas.setCaido(true);
        aprobar(ADMIN, "ADMIN", id).andExpect(status().isAccepted());
        // El cambio llegó a Entradas pero la respuesta se perdió
        entradas.setCaido(false);
        entradas.agregar(entradaId, CLIENTE, EstadoEntrada.EN_REINTEGRO, Instant.now().plus(Duration.ofDays(30)));

        reintentarReintegro(reintegroDe(id)).andExpect(status().isOk());

        assertThat(entradas.pasesAEnReintegro()).isZero();
        assertThat(solicitudes.findById(id).orElseThrow().coordinacionCompleta()).isTrue();
    }

    @Test
    void unaLiberacionRepetidaEnReservasNoLiberaDosVeces() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        UUID id = devolucionPendiente(CLIENTE, entradaId);
        aprobar(ADMIN, "ADMIN", id).andExpect(status().isOk());
        var compra = compras.findById(solicitudes.findById(id).orElseThrow().getCompraId()).orElseThrow();

        // Repetir la liberación con el mismo devolucionId no genera otra
        reservas.liberarPorDevolucion(compra.getReservaId(), id.toString(),
                reservas.liberadasPorDevolucion().get(id.toString()));

        assertThat(reservas.liberadasPorDevolucion()).hasSize(1);
    }

    @Test
    void reintentarUnReintegroFallidoDejaLaEntradaDevuelta() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        UUID id = devolucionPendiente(CLIENTE, entradaId);
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);
        aprobar(ADMIN, "ADMIN", id).andExpect(status().isAccepted());
        assertThat(entradas.estado(entradaId)).isEqualTo(EstadoEntrada.EN_REINTEGRO);

        reintentarReintegro(reintegroDe(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EXITOSO"))
                .andExpect(jsonPath("$.intentos").value(2));

        assertThat(pagos.reintentosDeReintegro()).isEqualTo(1);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
        assertThat(entradas.estado(entradaId)).isEqualTo(EstadoEntrada.DEVUELTA);
        assertThat(entradas.pasesADevuelta()).isEqualTo(1);
        assertThat(reintegros.findById(reintegroDe(id)).orElseThrow().getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
    }

    @Test
    void siElReintentoVuelveAFallarSigue202YLaEntradaEnReintegro() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        UUID id = devolucionPendiente(CLIENTE, entradaId);
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);
        pagos.setResultadoReintento(EstadoReintegroPagos.FALLIDO);
        aprobar(ADMIN, "ADMIN", id);

        reintentarReintegro(reintegroDe(id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.estado").value("FALLIDO"));

        assertThat(entradas.estado(entradaId)).isEqualTo(EstadoEntrada.EN_REINTEGRO);
    }
}
