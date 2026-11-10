package ticketya.web;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.beans.factory.annotation.Autowired;
import ticketya.modelo.CancelacionEvento;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EstadoCancelacion;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;
import ticketya.servicio.CancelacionService;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Reanudación automática de cancelaciones interrumpidas. La tarea programada está
 * desactivada en las pruebas: se llama directamente a reanudarVencidas().
 */
class ReanudacionCancelacionesTest extends PruebaDevoluciones {

    @Autowired
    CancelacionService cancelacionService;

    /** Cancelación guardada (como antes del 202) y tomada hace {@code hace}, sin procesar: el servicio se cayó. */
    protected void cancelacionInterrumpida(Duration hace) {
        Instant tomada = cancelaciones.saveAndFlush(new CancelacionEvento(EVENTO_ID, "Lluvia", Instant.now())).getTomadaEn();
        cancelaciones.retomar(EVENTO_ID, tomada, Instant.now().minus(hace).truncatedTo(ChronoUnit.MILLIS));
    }

    protected CancelacionEvento cancelacion() {
        return cancelaciones.findById(EVENTO_ID).orElseThrow();
    }

    protected List<Reintegro> reintegrosPorCancelacion(UUID compraId) {
        return reintegros.findByCompraIdAndOrigen(compraId, OrigenReintegro.CANCELACION_EVENTO);
    }

    @Test
    void unaCancelacionInterrumpidaSeRetomaSinOtroAviso() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        UUID compraId = detalle.get(0).getCompra().getId();
        cancelacionInterrumpida(Duration.ofMinutes(10));

        assertThat(cancelacionService.reanudarVencidas()).isEqualTo(1);

        assertThat(reintegrosPorCancelacion(compraId)).hasSize(1)
                .first().satisfies(r -> assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO));
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.PROCESADA);
    }

    @Test
    void unaCancelacionDentroDelPlazoNoSeRetoma() throws Exception {
        entradasConfirmadas(CLIENTE);
        cancelacionInterrumpida(Duration.ofMinutes(1));

        assertThat(cancelacionService.reanudarVencidas()).isZero();

        assertThat(entradas.marcaciones()).isZero();
        assertThat(pagos.pedidosDeReintegro()).isZero();
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.EN_PROCESO);
    }

    @Test
    void unaCancelacionProcesadaNoSeVuelveAProcesar() throws Exception {
        entradasConfirmadas(CLIENTE);
        cancelacionService.recibir(EVENTO_ID, "Lluvia", Instant.now());
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.PROCESADA);
        int marcaciones = entradas.marcaciones();
        int pedidos = pagos.pedidosDeReintegro();

        assertThat(cancelacionService.reanudarVencidas()).isZero();

        assertThat(entradas.marcaciones()).isEqualTo(marcaciones);
        assertThat(pagos.pedidosDeReintegro()).isEqualTo(pedidos);
    }

    @Test
    void alRetomarNoSeDuplicanReintegrosYElPendienteSePideConElMismoIdentificador() throws Exception {
        UUID primera = entradasConfirmadas(CLIENTE).get(0).getCompra().getId();
        UUID segunda = entradasConfirmadas("cliente-2").get(0).getCompra().getId();
        // Primera corrida: un reintegro queda sin respuesta de Pagos (registrado, pendiente)
        pagos.setProximosReintegrosSinRespuesta(1);
        cancelacionService.recibir(EVENTO_ID, "Lluvia", Instant.now());
        List<Reintegro> antes = List.of(reintegrosPorCancelacion(primera).get(0), reintegrosPorCancelacion(segunda).get(0));
        assertThat(antes).extracting(Reintegro::getEstado)
                .containsExactlyInAnyOrder(EstadoReintegro.PENDIENTE, EstadoReintegro.EXITOSO);
        // El servicio se cae: la cancelación queda en proceso y vencida
        cancelaciones.deleteAll();
        cancelacionInterrumpida(Duration.ofMinutes(10));

        assertThat(cancelacionService.reanudarVencidas()).isEqualTo(1);

        for (Reintegro r : antes) {
            List<Reintegro> ahora = reintegrosPorCancelacion(r.getCompraId());
            assertThat(ahora).hasSize(1);
            assertThat(ahora.get(0).getRefundRequestId()).isEqualTo(r.getRefundRequestId());
            assertThat(ahora.get(0).getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        }
        // Pagos creó un reintegro por compra: el pendiente recién ahora, sin duplicar el otro
        assertThat(pagos.reintegrosNuevos()).isEqualTo(2);
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.PROCESADA);
    }

    @Test
    void siUnaCompraVuelveAFallarLaCancelacionSigueEnProcesoHastaElProximoPlazo() throws Exception {
        UUID pendiente = compraPendiente("cliente-2");
        cancelacionInterrumpida(Duration.ofMinutes(10));
        pagos.setCaido(true);

        assertThat(cancelacionService.reanudarVencidas()).isEqualTo(1);

        assertThat(compras.findById(pendiente).orElseThrow().getEstado()).isEqualTo(EstadoCompra.PENDIENTE);
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.EN_PROCESO);
        // Recién tomada: antes de que pase el plazo, otra revisión no la toma
        assertThat(cancelacionService.reanudarVencidas()).isZero();
    }

    // ------------------------------------------------------------------ exclusión

    private MvcResult avisoRepetido() throws Exception {
        return mvc.perform(post("/eventos-cancelados").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventoId\":\"" + EVENTO_ID + "\",\"motivo\":\"Lluvia\"}")).andReturn();
    }

    @RepeatedTest(5)
    void laReanudacionYUnAvisoRepetidoALaVezLaProcesanUnaSolaVez() throws Exception {
        UUID compraId = entradasConfirmadas(CLIENTE).get(0).getCompra().getId();
        cancelacionInterrumpida(Duration.ofMinutes(10));

        List<Object> resultados = PagoTest.alMismoTiempo(
                () -> cancelacionService.reanudarVencidas(),
                this::avisoRepetido);

        assertThat(((MvcResult) resultados.get(1)).getResponse().getStatus()).isEqualTo(202);
        assertThat(entradas.marcaciones()).isEqualTo(1);
        assertThat(reintegrosPorCancelacion(compraId)).hasSize(1);
        assertThat(pagos.pedidosDeReintegro()).isEqualTo(1);
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.PROCESADA);
    }

    @RepeatedTest(5)
    void dosReanudacionesALaVezTomanCadaCancelacionUnaSolaVez() throws Exception {
        UUID compraId = entradasConfirmadas(CLIENTE).get(0).getCompra().getId();
        cancelacionInterrumpida(Duration.ofMinutes(10));

        List<Integer> tomadas = PagoTest.alMismoTiempo(
                () -> cancelacionService.reanudarVencidas(),
                () -> cancelacionService.reanudarVencidas());

        assertThat(tomadas.get(0) + tomadas.get(1)).isEqualTo(1);
        assertThat(entradas.marcaciones()).isEqualTo(1);
        assertThat(reintegrosPorCancelacion(compraId)).hasSize(1);
        assertThat(pagos.pedidosDeReintegro()).isEqualTo(1);
    }
}
