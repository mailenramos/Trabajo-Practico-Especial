package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import ticketya.cliente.PagosCliente.EstadoReintegroPagos;
import ticketya.modelo.AvisoPendiente;
import ticketya.modelo.EstadoAviso;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.EstadoSolicitud;
import ticketya.modelo.Reintegro;
import ticketya.modelo.TipoAviso;
import ticketya.servicio.AvisoService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Avisos de devolución aprobada, devolución rechazada y resultado del reintegro. */
class AvisosDeDevolucionTest extends PruebaDevoluciones {

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired AvisoService avisoService;
    @Autowired TransactionTemplate transaccion;

    private List<AvisoPendiente> deTipo(TipoAviso tipo) {
        return avisos.findByTipoOrderByIdAsc(tipo);
    }

    private JsonNode datos(AvisoPendiente a) {
        return json.readTree(a.getDatos());
    }

    @Test
    void aprobarGeneraElAvisoDeDevolucionAprobadaYElDelReintegroExitoso() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));

        aprobar(ADMIN, "ADMIN", id).andExpect(status().isOk());

        assertThat(deTipo(TipoAviso.DEVOLUCION_APROBADA)).singleElement().satisfies(a -> {
            assertThat(a.getAcontecimientoId()).isEqualTo("devolucion-aprobada-" + id);
            assertThat(a.getUsuarioId()).isEqualTo(CLIENTE);
            assertThat(a.getRecursoId()).isEqualTo(id.toString());
            assertThat(a.getEstado()).isEqualTo(EstadoAviso.PENDIENTE);
            assertThat(datos(a).get("evento").asString()).isEqualTo("Recital de prueba");
            assertThat(datos(a).get("monto").asString()).isEqualTo("15000.00");
        });
        Reintegro r = reintegros.findByRefundRequestId(id).orElseThrow();
        assertThat(deTipo(TipoAviso.RESULTADO_REINTEGRO)).singleElement().satisfies(a -> {
            assertThat(a.getAcontecimientoId()).isEqualTo("resultado-reintegro-" + r.getId() + "-EXITOSO");
            assertThat(a.getUsuarioId()).isEqualTo(CLIENTE);
            assertThat(a.getRecursoId()).isEqualTo(r.getId().toString());
            assertThat(datos(a).get("monto").asString()).isEqualTo("15000.00");
            assertThat(datos(a).get("resultado").asString()).isEqualTo("EXITOSO");
        });
    }

    @Test
    void rechazarGeneraElAvisoConElMotivo() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));

        rechazar(ADMIN, "ADMIN", id, "Fuera de las condiciones").andExpect(status().isOk());

        assertThat(deTipo(TipoAviso.DEVOLUCION_RECHAZADA)).singleElement().satisfies(a -> {
            assertThat(a.getAcontecimientoId()).isEqualTo("devolucion-rechazada-" + id);
            assertThat(a.getUsuarioId()).isEqualTo(CLIENTE);
            assertThat(datos(a).get("evento").asString()).isEqualTo("Recital de prueba");
            assertThat(datos(a).get("motivo").asString()).isEqualTo("Fuera de las condiciones");
        });
        assertThat(deTipo(TipoAviso.DEVOLUCION_APROBADA)).isEmpty();
        assertThat(deTipo(TipoAviso.RESULTADO_REINTEGRO)).isEmpty();
    }

    @Test
    void siElAvisoYaExisteElRechazoSeConfirmaIgualYNoSeDuplica() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));
        // El mismo aviso (acontecimiento, tipo y destinatario) ya está en el outbox
        transaccion.executeWithoutResult(t -> avisoService.registrar(TipoAviso.DEVOLUCION_RECHAZADA,
                "devolucion-rechazada-" + id, CLIENTE, id.toString(), Map.of("motivo", "registrado antes")));

        rechazar(ADMIN, "ADMIN", id, "Fuera de las condiciones").andExpect(status().isOk());

        // La operación de negocio no se revirtió: la solicitud quedó rechazada con su motivo
        var solicitud = solicitudes.findById(id).orElseThrow();
        assertThat(solicitud.getEstado()).isEqualTo(EstadoSolicitud.RECHAZADA);
        assertThat(solicitud.getMotivoRechazo()).isEqualTo("Fuera de las condiciones");
        // Y sigue habiendo un solo aviso: el que ya estaba
        assertThat(deTipo(TipoAviso.DEVOLUCION_RECHAZADA)).singleElement()
                .satisfies(a -> assertThat(datos(a).get("motivo").asString()).isEqualTo("registrado antes"));
    }

    @Test
    void unaSolicitudYaResueltaNoGeneraOtroAviso() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));
        rechazar(ADMIN, "ADMIN", id, "no").andExpect(status().isOk());

        rechazar(ADMIN, "ADMIN", id, "otra vez").andExpect(status().isConflict());
        aprobar(ADMIN, "ADMIN", id).andExpect(status().isConflict());

        assertThat(deTipo(TipoAviso.DEVOLUCION_RECHAZADA)).hasSize(1);
        assertThat(deTipo(TipoAviso.DEVOLUCION_APROBADA)).isEmpty();
    }

    @Test
    void unReintegroFallidoAvisaElFalloYSiDespuesSaleBienAvisaElExito() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);

        aprobar(ADMIN, "ADMIN", id).andExpect(status().is2xxSuccessful()); // 202: coordinación incompleta
        Reintegro r = reintegros.findByRefundRequestId(id).orElseThrow();
        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.FALLIDO);
        assertThat(deTipo(TipoAviso.RESULTADO_REINTEGRO)).singleElement().satisfies(a -> {
            assertThat(a.getAcontecimientoId()).isEqualTo("resultado-reintegro-" + r.getId() + "-FALLIDO");
            assertThat(datos(a).get("resultado").asString()).isEqualTo("FALLIDO");
        });

        pagos.setResultadoReintento(EstadoReintegroPagos.EXITOSO);
        reintentarReintegro(r.getId()).andExpect(status().is2xxSuccessful());

        assertThat(deTipo(TipoAviso.RESULTADO_REINTEGRO))
                .extracting(a -> datos(a).get("resultado").asString())
                .containsExactly("FALLIDO", "EXITOSO");
    }

    @Test
    void unReintegroSinRespuestaDePagosNoAvisaNingunResultado() throws Exception {
        UUID id = devolucionPendiente(CLIENTE, unaEntradaDePlatea(CLIENTE));
        pagos.setReintegrosCaidos(true);

        aprobar(ADMIN, "ADMIN", id).andExpect(status().is2xxSuccessful()); // 202: coordinación incompleta

        assertThat(reintegros.findByRefundRequestId(id).orElseThrow().getEstado()).isEqualTo(EstadoReintegro.PENDIENTE);
        assertThat(deTipo(TipoAviso.DEVOLUCION_APROBADA)).hasSize(1);
        assertThat(deTipo(TipoAviso.RESULTADO_REINTEGRO)).isEmpty();
    }
}
