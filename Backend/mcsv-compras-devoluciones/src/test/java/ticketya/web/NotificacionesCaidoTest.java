package ticketya.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import ticketya.modelo.AvisoPendiente;
import ticketya.modelo.EstadoAviso;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.EstadoSolicitud;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;
import ticketya.modelo.TipoAviso;
import ticketya.servicio.EnvioDeAvisosService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Con Notificaciones caído, la compra, la devolución y el reintegro se completan igual: los avisos
 * quedan guardados y la tarea de envío los reintenta sin tocar las operaciones.
 */
class NotificacionesCaidoTest extends PruebaDevoluciones {

    @Autowired EnvioDeAvisosService envio;

    @BeforeEach
    void notificacionesNoResponde() {
        notificaciones.setCaido(true);
    }

    @Test
    void laCompraSeConfirmaYSusAvisosQuedanPendientes() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);

        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));

        envio.enviarPendientes();

        assertThat(compras.findById(compraId).orElseThrow().getEstado()).isEqualTo(EstadoCompra.CONFIRMADA);
        assertThat(avisos.findAll()).hasSize(2).allSatisfy(a -> {
            assertThat(a.getEstado()).isEqualTo(EstadoAviso.PENDIENTE);
            assertThat(a.getIntentos()).isEqualTo(1);
            assertThat(a.getUltimoError()).contains("no responde");
        });
    }

    @Test
    void laDevolucionYSuReintegroSeCompletanIgual() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));

        aprobar(ADMIN, "ADMIN", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coordinacion.completa").value(true))
                .andExpect(jsonPath("$.reintegro.estado").value("EXITOSO"));
        envio.enviarPendientes();

        assertThat(solicitudes.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoSolicitud.APROBADA);
        assertThat(reintegros.findByRefundRequestId(id).orElseThrow().getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(avisos.findByTipoOrderByIdAsc(TipoAviso.DEVOLUCION_APROBADA)).singleElement()
                .extracting(AvisoPendiente::getEstado).isEqualTo(EstadoAviso.PENDIENTE);
        assertThat(avisos.findByTipoOrderByIdAsc(TipoAviso.RESULTADO_REINTEGRO)).singleElement()
                .extracting(AvisoPendiente::getEstado).isEqualTo(EstadoAviso.PENDIENTE);
    }

    @Test
    void elRechazoDeUnaDevolucionSeCompletaIgual() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));

        rechazar(ADMIN, "ADMIN", id, "Fuera de término").andExpect(status().isOk());
        envio.enviarPendientes();

        assertThat(solicitudes.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoSolicitud.RECHAZADA);
        assertThat(avisos.findByTipoOrderByIdAsc(TipoAviso.DEVOLUCION_RECHAZADA)).singleElement()
                .extracting(AvisoPendiente::getEstado).isEqualTo(EstadoAviso.PENDIENTE);
    }

    @Test
    void laCancelacionDeUnEventoReintegraIgual() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);
        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD").andExpect(status().isOk());

        mvc.perform(post("/eventos-cancelados").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventoId\":\"" + EVENTO_ID + "\"}"))
                .andExpect(status().isAccepted());
        envio.enviarPendientes();

        Reintegro r = reintegros.findByCompraIdAndOrigen(compraId, OrigenReintegro.CANCELACION_EVENTO).get(0);
        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(avisos.findByTipoOrderByIdAsc(TipoAviso.EVENTO_CANCELADO)).singleElement()
                .extracting(AvisoPendiente::getEstado).isEqualTo(EstadoAviso.PENDIENTE);
    }

    @Test
    void cuandoNotificacionesVuelveLosAvisosSeEnvian() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);
        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD").andExpect(status().isOk());
        envio.enviarPendientes();

        notificaciones.setCaido(false);
        jdbcAdelantarAvisos();
        assertThat(envio.enviarPendientes()).isEqualTo(2);

        assertThat(avisos.findAll()).allMatch(a -> a.getEstado() == EstadoAviso.ENVIADO);
        assertThat(notificaciones.recibidos()).hasSize(2);
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private void jdbcAdelantarAvisos() {
        jdbc.update("update avisos_pendientes set proximo_intento = '2000-01-01 00:00:00'");
    }
}
