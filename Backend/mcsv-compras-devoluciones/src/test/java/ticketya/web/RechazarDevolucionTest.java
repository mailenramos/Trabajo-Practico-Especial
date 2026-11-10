package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.cliente.EntradasCliente.EstadoEntrada;
import ticketya.modelo.EstadoSolicitud;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D-2 y P-2: rechazar una solicitud de devolución. */
class RechazarDevolucionTest extends PruebaDevoluciones {

    @Test
    void elAdministradorRechazaConMotivoYLaEntradaSigueVigente() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        UUID id = devolucionPendiente(CLIENTE, entradaId);

        rechazar(ADMIN, "ADMIN", id, "La entrada ya fue usada")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADA"))
                .andExpect(jsonPath("$.motivoRechazo").value("La entrada ya fue usada"))
                .andExpect(jsonPath("$.resueltaEn").isNotEmpty())
                .andExpect(jsonPath("$.reintegro").doesNotExist());

        assertThat(solicitudes.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoSolicitud.RECHAZADA);
        assertThat(entradas.estado(entradaId)).isEqualTo(EstadoEntrada.VIGENTE);
        assertThat(entradas.pasesAEnReintegro()).isZero();
        assertThat(reservas.pedidosDeLiberacionPorDevolucion()).isZero();
        assertThat(pagos.pedidosDeReintegro()).isZero();
        assertThat(reintegros.count()).isZero();
    }

    @Test
    void rechazarSinMotivoDaErrorDeValidacion() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));

        rechazar(ADMIN, "ADMIN", id, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDACION"))
                .andExpect(jsonPath("$.campos.motivo").exists());
        rechazar(ADMIN, "ADMIN", id, "   ").andExpect(status().isBadRequest());

        assertThat(solicitudes.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoSolicitud.PENDIENTE);
    }

    @Test
    void unUsuarioQueNoEsAdministradorNoPuedeRechazar() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));

        rechazar(CLIENTE, "CLIENTE", id, "no quiero")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SOLO_ADMINISTRADOR"));

        assertThat(solicitudes.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoSolicitud.PENDIENTE);
    }

    @Test
    void unaSolicitudYaResueltaNoSeRechazaDeNuevo() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));
        rechazar(ADMIN, "ADMIN", id, "primera").andExpect(status().isOk());

        rechazar(ADMIN, "ADMIN", id, "segunda")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DEVOLUCION_YA_RESUELTA"));

        assertThat(solicitudes.findById(id).orElseThrow().getMotivoRechazo()).isEqualTo("primera");
    }

    @Test
    void rechazarUnaInexistenteDa404() throws Exception {
        rechazar(ADMIN, "ADMIN", UUID.randomUUID(), "x")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DEVOLUCION_NO_ENCONTRADA"));
    }
}
