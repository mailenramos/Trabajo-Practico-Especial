package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import ticketya.cliente.EntradasCliente.EstadoEntrada;
import ticketya.modelo.EstadoSolicitud;
import ticketya.modelo.SolicitudDevolucion;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D-1: solicitar la devolución de una entrada. */
class SolicitarDevolucionTest extends PruebaDevoluciones {

    @Test
    void solicitudDentroDelPlazoQuedaPendienteConElPrecioDeLaEntrada() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);

        solicitarDevolucion(CLIENTE, entradaId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.entradaId").value(entradaId))
                .andExpect(jsonPath("$.monto").value(15000.00))
                .andExpect(jsonPath("$.lugar.sectorNombre").value("Platea"))
                .andExpect(jsonPath("$.lugar.fila").value(10))
                .andExpect(jsonPath("$.coordinacion").doesNotExist())
                .andExpect(jsonPath("$.reintegro").doesNotExist());

        SolicitudDevolucion s = solicitudes.findByEntradaActiva(entradaId).orElseThrow();
        assertThat(s.getEstado()).isEqualTo(EstadoSolicitud.PENDIENTE);
        assertThat(s.getClienteId()).isEqualTo(CLIENTE);
        assertThat(reservas.pedidosDeLiberacionPorDevolucion()).isZero();
        assertThat(entradas.pasesAEnReintegro()).isZero();
    }

    @Test
    void conElEventoAMenosDe72HorasSeRechazaPorPlazoVencido() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        eventoQueEmpiezaEn(Duration.ofHours(71), "publicado");

        solicitarDevolucion(CLIENTE, entradaId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PLAZO_VENCIDO"));

        assertThat(solicitudes.count()).isZero();
    }

    @Test
    void elLimiteDe72HorasExactasYaEstaVencidoYUnMinutoMasNo() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);

        eventoQueEmpiezaEn(Duration.ofHours(72), "publicado");
        solicitarDevolucion(CLIENTE, entradaId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PLAZO_VENCIDO"));

        eventoQueEmpiezaEn(Duration.ofHours(72).plusMinutes(1), "publicado");
        solicitarDevolucion(CLIENTE, entradaId).andExpect(status().isCreated());
    }

    @Test
    void unaEntradaQueNoEstaVigenteSeRechaza() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        entradas.agregar(entradaId, CLIENTE, EstadoEntrada.DEVUELTA, Instant.now().plus(Duration.ofDays(30)));

        solicitarDevolucion(CLIENTE, entradaId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ENTRADA_NO_VIGENTE"));

        assertThat(solicitudes.count()).isZero();
    }

    @Test
    void laEntradaDeOtroClienteSeRechaza() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);

        solicitarDevolucion("cliente-2", entradaId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ENTRADA_AJENA"));

        assertThat(solicitudes.count()).isZero();
    }

    @Test
    void unaEntradaInexistenteDa404() throws Exception {
        solicitarDevolucion(CLIENTE, "no-existe")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ENTRADA_NO_ENCONTRADA"));
    }

    @Test
    void unaEntradaDeUnaCompraQueEntradasNoEncuentraDa404() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        entradas.olvidar(entradaId);

        solicitarDevolucion(CLIENTE, entradaId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ENTRADA_NO_ENCONTRADA"));
        assertThat(solicitudes.findByEntradaActiva(entradaId)).isEmpty();
    }

    @Test
    void laEntradaDeUnEventoCanceladoEnMinusculasSeRechaza() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        eventoQueEmpiezaEn(Duration.ofDays(30), "cancelado");

        solicitarDevolucion(CLIENTE, entradaId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EVENTO_CANCELADO"));
    }

    @Test
    void repetirLaSolicitudDevuelveLaPendienteSinDuplicar() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        UUID id = devolucionPendiente(CLIENTE, entradaId);

        solicitarDevolucion(CLIENTE, entradaId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

        assertThat(solicitudes.count()).isEqualTo(1);
    }

    @Test
    void dosSolicitudesSimultaneasDeLaMismaEntradaRegistranUna() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);

        List<MvcResult> resultados = PagoTest.alMismoTiempo(
                () -> solicitarDevolucion(CLIENTE, entradaId).andReturn(),
                () -> solicitarDevolucion(CLIENTE, entradaId).andReturn(),
                () -> solicitarDevolucion(CLIENTE, entradaId).andReturn());

        assertThat(solicitudes.count()).isEqualTo(1);
        String id = solicitudes.findByEntradaActiva(entradaId).orElseThrow().getId().toString();
        assertThat(resultados).extracting(r -> r.getResponse().getStatus()).containsOnly(201, 200);
        assertThat(resultados).allSatisfy(r ->
                assertThat(r.getResponse().getContentAsString()).contains("\"id\":\"" + id + "\""));
    }

    @Test
    void despuesDeUnRechazoSePuedeSolicitarDeNuevo() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        UUID primera = devolucionPendiente(CLIENTE, entradaId);
        solicitudes.rechazar(primera, "No corresponde", ADMIN, Instant.now());

        solicitarDevolucion(CLIENTE, entradaId).andExpect(status().isCreated());

        UUID segunda = solicitudes.findByEntradaActiva(entradaId).orElseThrow().getId();
        assertThat(segunda).isNotEqualTo(primera);
        assertThat(solicitudes.count()).isEqualTo(2);
    }

    @Test
    void siEntradasNoRespondeDa503SinRegistrarNada() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        entradas.setCaido(true);

        solicitarDevolucion(CLIENTE, entradaId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICIO_NO_DISPONIBLE"));

        assertThat(solicitudes.count()).isZero();
    }

    @Test
    void siEventosNoRespondeDa503SinRegistrarNada() throws Exception {
        String entradaId = unaEntradaDePlatea(CLIENTE);
        eventos.setCaido(true);

        solicitarDevolucion(CLIENTE, entradaId).andExpect(status().isServiceUnavailable());

        assertThat(solicitudes.count()).isZero();
    }

    @Test
    void sinEntradaIdDaErrorDeValidacion() throws Exception {
        mvc.perform(post("/devoluciones").header("X-User-Id", CLIENTE)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.entradaId").exists());
    }
}
