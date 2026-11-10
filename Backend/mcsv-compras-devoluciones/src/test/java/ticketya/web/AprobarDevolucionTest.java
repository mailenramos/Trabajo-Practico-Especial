package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import ticketya.modelo.EstadoSolicitud;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D-2 y P-2: aprobar una solicitud de devolución. */
class AprobarDevolucionTest extends PruebaDevoluciones {

    // ------------------------------------------------------------------ 5.2 transición

    @Test
    void aprobarDejaLaSolicitudAprobadaConSuReintegroRegistrado() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        UUID id = devolucionPendiente(CLIENTE, entradaId);

        aprobar(ADMIN, "ADMIN", id).andExpect(jsonPath("$.estado").value("APROBADA"));

        assertThat(solicitudes.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoSolicitud.APROBADA);
        assertThat(solicitudes.findById(id).orElseThrow().getResueltaPor()).isEqualTo(ADMIN);
        Reintegro r = reintegros.findByRefundRequestId(id).orElseThrow();
        assertThat(r.getMonto()).isEqualByComparingTo("15000.00");
        assertThat(r.getOrigen()).isEqualTo(OrigenReintegro.DEVOLUCION);
        assertThat(r.getMotivo()).isEqualTo("Devolución de entrada " + entradaId);
    }

    @Test
    void aprobarUnaSolicitudYaResueltaDa409() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));
        rechazar(ADMIN, "ADMIN", id, "no").andExpect(status().isOk());

        aprobar(ADMIN, "ADMIN", id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DEVOLUCION_YA_RESUELTA"));

        assertThat(solicitudes.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoSolicitud.RECHAZADA);
        assertThat(reintegros.count()).isZero();
    }

    @Test
    void dosAprobacionesSimultaneasCreanUnSoloReintegro() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));
        pagos.setDemoraMs(0);

        List<MvcResult> resultados = PagoTest.alMismoTiempo(
                () -> aprobar(ADMIN, "ADMIN", id).andReturn(),
                () -> aprobar("admin-2", "ADMIN", id).andReturn());

        assertThat(resultados).extracting(r -> r.getResponse().getStatus())
                .filteredOn(s -> s == 409).hasSize(1);
        assertThat(reintegros.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 5.3 coordinación

    @Test
    void aprobacionCompletaEnElOrdenDeLosCuatroPasos() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        UUID id = devolucionPendiente(CLIENTE, entradaId);

        aprobar(ADMIN, "ADMIN", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.coordinacion.completa").value(true))
                .andExpect(jsonPath("$.coordinacion.pasoPendiente").doesNotExist())
                .andExpect(jsonPath("$.reintegro.estado").value("EXITOSO"));

        // (a) entrada en reintegro y (d) devuelta
        assertThat(entradas.pasesAEnReintegro()).isEqualTo(1);
        assertThat(entradas.pasesADevuelta()).isEqualTo(1);
        assertThat(entradas.estado(entradaId)).isEqualTo(ticketya.cliente.EntradasCliente.EstadoEntrada.DEVUELTA);
        // (b) solo ese lugar, con el devolucionId
        var liberado = reservas.liberadasPorDevolucion().get(id.toString());
        assertThat(liberado).isEqualTo(new ticketya.cliente.ReservasCliente.LugarDevuelto(PLATEA, 10, 1));
        // (c) reintegro del precio unitario, con refundRequestId = devolucionId
        var enPagos = pagos.reintegroPorSolicitud(id.toString()).orElseThrow();
        assertThat(enPagos.amount()).isEqualByComparingTo("15000.00");
        assertThat(enPagos.reason()).isEqualTo("Devolución de entrada " + entradaId);
        assertThat(enPagos.paymentId()).isEqualTo(compras.findById(
                solicitudes.findById(id).orElseThrow().getCompraId()).orElseThrow().getPagoId());
        assertThat(solicitudes.findById(id).orElseThrow().coordinacionCompleta()).isTrue();
    }

    @Test
    void laDevolucionDeUnLugarNoNumeradoLiberaUnaUnidadDelSector() throws Exception {
        String entradaCampo = entradasConfirmadas(CLIENTE).get(2).getEntradaId();
        UUID id = devolucionPendiente(CLIENTE, entradaCampo);

        aprobar(ADMIN, "ADMIN", id).andExpect(status().isOk());

        assertThat(reservas.liberadasPorDevolucion().get(id.toString()))
                .isEqualTo(new ticketya.cliente.ReservasCliente.LugarDevuelto(CAMPO, null, null));
        assertThat(pagos.reintegroPorSolicitud(id.toString()).orElseThrow().amount()).isEqualByComparingTo("8000.50");
    }

    @Test
    void conElReintegroFallidoLaSolicitudQuedaAprobadaYLaEntradaEnReintegro() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        UUID id = devolucionPendiente(CLIENTE, entradaId);
        pagos.setResultadoReintegro(ticketya.cliente.PagosCliente.EstadoReintegroPagos.FALLIDO);

        aprobar(ADMIN, "ADMIN", id)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.reintegro.estado").value("FALLIDO"))
                .andExpect(jsonPath("$.coordinacion.completa").value(false))
                .andExpect(jsonPath("$.coordinacion.pasoPendiente").value("REINTEGRO"));

        assertThat(entradas.estado(entradaId)).isEqualTo(ticketya.cliente.EntradasCliente.EstadoEntrada.EN_REINTEGRO);
        assertThat(entradas.pasesADevuelta()).isZero();
        assertThat(reservas.liberadasPorDevolucion()).containsKey(id.toString());
    }

    @Test
    void dosEntradasDeLaMismaCompraGeneranUnReintegroCadaUna() throws Exception {
        var entradasCompra = entradasConfirmadas(CLIENTE);
        UUID a = devolucionPendiente(CLIENTE, entradasCompra.get(0).getEntradaId());
        UUID b = devolucionPendiente(CLIENTE, entradasCompra.get(1).getEntradaId());

        aprobar(ADMIN, "ADMIN", a).andExpect(status().isOk());
        aprobar(ADMIN, "ADMIN", b).andExpect(status().isOk());

        assertThat(pagos.reintegrosNuevos()).isEqualTo(2);
        assertThat(reintegros.findByCompraId(entradasCompra.get(0).getCompra().getId())).hasSize(2);
    }

    @Test
    void unUsuarioQueNoEsAdministradorNoPuedeAprobar() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));

        aprobar(CLIENTE, "CLIENTE", id)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SOLO_ADMINISTRADOR"));

        assertThat(solicitudes.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoSolicitud.PENDIENTE);
        assertThat(reintegros.count()).isZero();
    }

    @Test
    void aprobarUnaInexistenteDa404() throws Exception {
        aprobar(ADMIN, "ADMIN", UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DEVOLUCION_NO_ENCONTRADA"));
    }
}
