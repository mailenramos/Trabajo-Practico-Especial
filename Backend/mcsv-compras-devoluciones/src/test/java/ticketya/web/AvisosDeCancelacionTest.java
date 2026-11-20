package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import ticketya.PruebaIntegracion;
import ticketya.modelo.AvisoPendiente;
import ticketya.modelo.EstadoAviso;
import ticketya.modelo.TipoAviso;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Al cancelarse un evento se genera un aviso EVENTO_CANCELADO por cada comprador afectado. */
class AvisosDeCancelacionTest extends PruebaIntegracion {

    private final JsonMapper json = JsonMapper.builder().build();

    private UUID compraConfirmada(String clienteId) throws Exception {
        UUID compraId = compraPendiente(clienteId);
        pagar(clienteId, compraId, "clave-" + UUID.randomUUID(), "CREDIT_CARD").andExpect(status().isOk());
        return compraId;
    }

    private ResultActions avisarCancelacion() throws Exception {
        return mvc.perform(post("/eventos-cancelados").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventoId\":\"" + EVENTO_ID + "\",\"motivo\":\"Lluvia\"}"));
    }

    @Test
    void unAvisoPorCompradorConfirmadoSinRepetirClientes() throws Exception {
        compraConfirmada("c1");
        compraConfirmada("c1");
        compraConfirmada("c2");
        compraPendiente("c3"); // no llegó a comprar: no recibe aviso de cancelación

        avisarCancelacion().andExpect(status().isAccepted());

        var cancelados = avisos.findByTipoOrderByIdAsc(TipoAviso.EVENTO_CANCELADO);
        assertThat(cancelados).extracting(AvisoPendiente::getUsuarioId).containsExactlyInAnyOrder("c1", "c2");
        for (AvisoPendiente a : cancelados) {
            assertThat(a.getAcontecimientoId()).isEqualTo("evento-cancelado-" + EVENTO_ID);
            assertThat(a.getRecursoId()).isEqualTo(EVENTO_ID);
            assertThat(a.getEstado()).isEqualTo(EstadoAviso.PENDIENTE);
            JsonNode datos = json.readTree(a.getDatos());
            assertThat(datos.get("evento").asString()).isEqualTo("Recital de prueba");
            assertThat(datos.get("fecha").asString()).isEqualTo("01/12/2026 18:00");
        }
    }

    @Test
    void unAvisoDeCancelacionRepetidoNoGeneraAvisosNuevos() throws Exception {
        compraConfirmada("c1");
        avisarCancelacion().andExpect(status().isAccepted());

        avisarCancelacion();
        avisarCancelacion();

        assertThat(avisos.findByTipoOrderByIdAsc(TipoAviso.EVENTO_CANCELADO)).hasSize(1);
    }

    @Test
    void unEventoSinComprasConfirmadasNoGeneraAvisos() throws Exception {
        compraPendiente("c1");

        avisarCancelacion().andExpect(status().isAccepted());

        assertThat(avisos.findByTipoOrderByIdAsc(TipoAviso.EVENTO_CANCELADO)).isEmpty();
    }
}
