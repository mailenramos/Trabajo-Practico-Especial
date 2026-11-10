package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import ticketya.cliente.EntradasCliente.EstadoEntrada;
import ticketya.cliente.PagosCliente.EstadoReintegroPagos;
import ticketya.modelo.CancelacionEvento;
import ticketya.modelo.Compra;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EntradaDeReintegro;
import ticketya.modelo.EstadoCancelacion;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.EstadoSolicitud;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;
import ticketya.modelo.SolicitudDevolucion;
import ticketya.servicio.CancelacionService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** E-1 y E-2: aviso de cancelación de un evento y reintegro automático de sus compras. */
class CancelacionEventosTest extends PruebaDevoluciones {

    /** 2 de platea (15000.00) y 2 de campo (8000.50). */
    private static final BigDecimal TOTAL = new BigDecimal("46001.00");

    @Autowired
    CancelacionService cancelacionService;

    private ResultActions avisar(String cuerpo) throws Exception {
        return mvc.perform(post("/eventos-cancelados").contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private ResultActions avisarCancelacion() throws Exception {
        return avisar("{\"eventoId\":\"" + EVENTO_ID + "\",\"motivo\":\"Lluvia\","
                + "\"canceladoEn\":\"2026-11-08T12:00:00Z\"}");
    }

    private List<Reintegro> reintegrosPorCancelacion(UUID compraId) {
        return reintegros.findByCompraIdAndOrigen(compraId, OrigenReintegro.CANCELACION_EVENTO);
    }

    private Reintegro elReintegroPorCancelacion(UUID compraId) {
        List<Reintegro> lista = reintegrosPorCancelacion(compraId);
        assertThat(lista).hasSize(1);
        return lista.get(0);
    }

    private UUID compraDe(List<DetalleCompra> entradas) {
        return entradas.get(0).getCompra().getId();
    }

    private CancelacionEvento cancelacion() {
        return cancelaciones.findById(EVENTO_ID).orElseThrow();
    }

    // ------------------------------------------------------------------ aviso

    @Test
    void unAvisoNuevoRespondeAceptadoYDevuelveLaCompraConfirmada() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);

        avisarCancelacion()
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventoId").value(EVENTO_ID))
                .andExpect(jsonPath("$.resultado").value("ACEPTADO"));

        Reintegro r = elReintegroPorCancelacion(compraDe(detalle));
        assertThat(r.getMonto()).isEqualByComparingTo(TOTAL);
        assertThat(r.getMotivo()).isEqualTo("Evento cancelado");
        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(pagos.reintegroPorSolicitud(r.getRefundRequestId().toString()).orElseThrow().reason())
                .isEqualTo("Evento cancelado");
        assertThat(detalle).allSatisfy(d ->
                assertThat(entradas.estado(d.getEntradaId())).isEqualTo(EstadoEntrada.DEVUELTA));
        assertThat(entradasDeReintegro.findByReintegroIdOrderByIdAsc(r.getId()))
                .hasSize(4).allMatch(EntradaDeReintegro::isDevuelta);
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.PROCESADA);
        assertThat(cancelacion().getEntradasMarcadas()).isEqualTo(4);
        assertThat(cancelacion().getMotivo()).isEqualTo("Lluvia");
    }

    @Test
    void unAvisoRepetidoDeUnEventoProcesadoResponde200SinOtroReintegro() throws Exception {
        entradasConfirmadas(CLIENTE);
        avisarCancelacion().andExpect(status().isAccepted());

        avisarCancelacion()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("YA_PROCESADO"));

        assertThat(pagos.pedidosDeReintegro()).isEqualTo(1);
        assertThat(entradas.marcaciones()).isEqualTo(1);
    }

    @Test
    void unAvisoRepetidoMientrasSeProcesaResponde202SinOtroProcesamiento() throws Exception {
        entradasConfirmadas(CLIENTE);
        cancelaciones.saveAndFlush(new CancelacionEvento(EVENTO_ID, "Lluvia", Instant.now()));

        avisarCancelacion().andExpect(status().isAccepted());

        assertThat(entradas.marcaciones()).isZero();
        assertThat(pagos.pedidosDeReintegro()).isZero();
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.EN_PROCESO);
    }

    @Test
    void unaCancelacionEnProcesoVencidaSeRetomaConElProximoAviso() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        Instant tomada = cancelaciones.saveAndFlush(new CancelacionEvento(EVENTO_ID, "Lluvia", Instant.now())).getTomadaEn();
        // Simula que el servicio se cayó hace 10 minutos con la cancelación tomada
        cancelaciones.retomar(EVENTO_ID, tomada, Instant.now().minus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.MILLIS));

        avisarCancelacion().andExpect(status().isAccepted());

        assertThat(elReintegroPorCancelacion(compraDe(detalle)).getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.PROCESADA);
    }

    @Test
    void unAvisoSinEventoIdSeRechazaConErrorDeValidacion() throws Exception {
        avisar("{\"motivo\":\"Lluvia\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDACION"))
                .andExpect(jsonPath("$.campos.eventoId").exists());

        assertThat(cancelaciones.count()).isZero();
    }

    // ------------------------------------------------------------------ procesamiento

    @Test
    void unaEntradaYaDevueltaNoSeVuelveAReintegrar() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        aprobar(ADMIN, "ADMIN", devolucionPendiente(CLIENTE, detalle.get(0).getEntradaId()))
                .andExpect(status().isOk());

        avisarCancelacion().andExpect(status().isAccepted());

        Reintegro r = elReintegroPorCancelacion(compraDe(detalle));
        assertThat(r.getMonto()).isEqualByComparingTo(TOTAL.subtract(new BigDecimal("15000.00")));
        assertThat(entradasDeReintegro.findByReintegroIdOrderByIdAsc(r.getId()))
                .extracting(EntradaDeReintegro::getEntradaId)
                .doesNotContain(detalle.get(0).getEntradaId())
                .hasSize(3);
    }

    @Test
    void unaCompraSinEntradasVigentesNoGeneraReintegro() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        for (DetalleCompra d : detalle) {
            aprobar(ADMIN, "ADMIN", devolucionPendiente(CLIENTE, d.getEntradaId())).andExpect(status().isOk());
        }
        int pedidosAntes = pagos.pedidosDeReintegro();

        avisarCancelacion().andExpect(status().isAccepted());

        assertThat(reintegrosPorCancelacion(compraDe(detalle))).isEmpty();
        assertThat(pagos.pedidosDeReintegro()).isEqualTo(pedidosAntes);
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.PROCESADA);
    }

    /** Compra pendiente cobrada: pago aprobado y emisión fallida tras los 3 intentos. */
    private UUID compraPendienteCobrada(String clienteId) throws Exception {
        UUID id = compraPendiente(clienteId);
        entradas.fallarProximas(3);
        pagar(clienteId, id, "clave-" + clienteId, "CREDIT_CARD").andExpect(status().isAccepted());
        entradas.fallarProximas(0);
        Compra compra = compras.findById(id).orElseThrow();
        assertThat(compra.getEstado()).isEqualTo(EstadoCompra.PENDIENTE);
        assertThat(compra.getPagoId()).isNotNull();
        return id;
    }

    @Test
    void unaCompraPendienteCobradaQuedaFallidaConUnReintegroPorElTotal() throws Exception {
        UUID cobrada = compraPendienteCobrada("cliente-2");
        UUID fallida = compraPendiente("cliente-3");
        pagar("cliente-3", fallida, "clave-f", "DIGITAL_WALLET");

        avisarCancelacion().andExpect(status().isAccepted());

        Compra compra = compras.findById(cobrada).orElseThrow();
        assertThat(compra.getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        Reintegro r = elReintegroPorCancelacion(cobrada);
        assertThat(r.getMonto()).isEqualByComparingTo(compra.getTotal());
        assertThat(r.getMotivo()).isEqualTo("Evento cancelado");
        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        var enPagos = pagos.reintegroPorSolicitud(r.getRefundRequestId().toString()).orElseThrow();
        assertThat(enPagos.reason()).isEqualTo("Evento cancelado");
        assertThat(enPagos.paymentId()).isEqualTo(compra.getPagoId());
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);

        assertThat(compras.findById(fallida).orElseThrow().getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        assertThat(reintegros.findByCompraIdOrderByCreadoEnAsc(fallida)).isEmpty();
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.PROCESADA);
    }

    @Test
    void elReintegroFallidoDeUnaCompraPendienteCerradaSeReintentaSobreElMismoReintegro() throws Exception {
        UUID cobrada = compraPendienteCobrada("cliente-2");
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);

        avisarCancelacion().andExpect(status().isAccepted());

        assertThat(compras.findById(cobrada).orElseThrow().getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        Reintegro fallido = elReintegroPorCancelacion(cobrada);
        assertThat(fallido.getEstado()).isEqualTo(EstadoReintegro.FALLIDO);

        reintentarReintegro(fallido.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EXITOSO"));

        Reintegro r = elReintegroPorCancelacion(cobrada);
        assertThat(r.getId()).isEqualTo(fallido.getId());
        assertThat(r.getRefundRequestId()).isEqualTo(fallido.getRefundRequestId());
        assertThat(pagos.reintentosDeReintegro()).isEqualTo(1);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
    }

    @Test
    void unaCompraPendienteCobradaSinRespuestaUsaElPagoAprobadoQueInformaPagos() throws Exception {
        UUID id = compraPendiente("cliente-2");
        pagos.setPerderRespuesta(true);
        pagar("cliente-2", id, "clave-1", "CREDIT_CARD");
        pagos.setPerderRespuesta(false);
        assertThat(compras.findById(id).orElseThrow().getPagoId()).isNull();
        String pagoEnPagos = pagos.listarPagosPorCompra(id.toString()).getFirst().paymentId();

        avisarCancelacion().andExpect(status().isAccepted());

        Compra compra = compras.findById(id).orElseThrow();
        assertThat(compra.getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        assertThat(compra.getPagoId()).isEqualTo(pagoEnPagos);
        Reintegro r = elReintegroPorCancelacion(id);
        assertThat(r.getMonto()).isEqualByComparingTo(compra.getTotal());
        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(pagos.reintegroPorSolicitud(r.getRefundRequestId().toString()).orElseThrow().paymentId())
                .isEqualTo(pagoEnPagos);
    }

    @Test
    void unaCompraPendienteSinCobroQuedaFallidaSinReintegro() throws Exception {
        UUID sinPagos = compraPendiente("cliente-2");
        UUID soloRechazado = compraPendiente("cliente-3");
        pagos.registrarPago(soloRechazado.toString(), TOTAL, ticketya.cliente.PagosCliente.EstadoPago.RECHAZADO);
        int consultas = pagos.consultasPorCompra();

        avisarCancelacion().andExpect(status().isAccepted());

        for (UUID id : List.of(sinPagos, soloRechazado)) {
            Compra compra = compras.findById(id).orElseThrow();
            assertThat(compra.getEstado()).isEqualTo(EstadoCompra.FALLIDA);
            assertThat(compra.getPagoId()).isNull();
            assertThat(reintegros.findByCompraIdOrderByCreadoEnAsc(id)).isEmpty();
        }
        assertThat(pagos.consultasPorCompra()).isEqualTo(consultas + 2);
        assertThat(pagos.pedidosDeReintegro()).isZero();
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.PROCESADA);
    }

    @Test
    void siPagosNoRespondeLaConsultaLaCompraSiguePendienteYLaCancelacionQuedaEnProceso() throws Exception {
        List<DetalleCompra> confirmada = entradasConfirmadas(CLIENTE);
        UUID pendiente = compraPendiente("cliente-2");
        pagos.setCaido(true);

        avisarCancelacion().andExpect(status().isAccepted());

        assertThat(compras.findById(pendiente).orElseThrow().getEstado()).isEqualTo(EstadoCompra.PENDIENTE);
        assertThat(reintegros.findByCompraIdOrderByCreadoEnAsc(pendiente)).isEmpty();
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.EN_PROCESO);
        assertThat(cancelacion().getUltimoError()).contains("COMPRA " + pendiente);
        // La compra confirmada del mismo evento recibe igual su reintegro
        assertThat(elReintegroPorCancelacion(compraDe(confirmada)).getEstado()).isEqualTo(EstadoReintegro.EXITOSO);

        // Con Pagos de vuelta, retomar la cancelación cierra la compra pendiente
        pagos.setCaido(false);
        cancelacionService.procesar(EVENTO_ID);
        assertThat(compras.findById(pendiente).orElseThrow().getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.PROCESADA);
    }

    @Test
    void alRetomarSeVuelveAPedirElReintegroSinRespuestaDeUnaCompraCerradaSinDuplicarlo() throws Exception {
        UUID cobrada = compraPendienteCobrada("cliente-2");
        pagos.setProximosReintegrosSinRespuesta(1);

        avisarCancelacion().andExpect(status().isAccepted());
        Reintegro pendiente = elReintegroPorCancelacion(cobrada);
        assertThat(pendiente.getEstado()).isEqualTo(EstadoReintegro.PENDIENTE);
        assertThat(compras.findById(cobrada).orElseThrow().getEstado()).isEqualTo(EstadoCompra.FALLIDA);

        cancelacionService.procesar(EVENTO_ID);

        Reintegro r = elReintegroPorCancelacion(cobrada);
        assertThat(r.getId()).isEqualTo(pendiente.getId());
        assertThat(r.getRefundRequestId()).isEqualTo(pendiente.getRefundRequestId());
        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(pagos.pedidosDeReintegro()).isEqualTo(2);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
    }

    @Test
    void unAvisoRepetidoNoVuelveACerrarNiAReintegrarUnaCompraPendienteCobrada() throws Exception {
        UUID cobrada = compraPendienteCobrada("cliente-2");
        avisarCancelacion().andExpect(status().isAccepted());
        Reintegro primero = elReintegroPorCancelacion(cobrada);

        avisarCancelacion()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("YA_PROCESADO"));
        cancelacionService.procesar(EVENTO_ID);

        assertThat(reintegrosPorCancelacion(cobrada)).hasSize(1)
                .first().satisfies(r -> assertThat(r.getId()).isEqualTo(primero.getId()));
        assertThat(compras.findById(cobrada).orElseThrow().getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        assertThat(pagos.pedidosDeReintegro()).isEqualTo(1);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
    }

    @Test
    void lasEntradasConocidasDeUnaCompraPendienteCerradaPasanADevuelta() throws Exception {
        // Emisión hecha y asignada, pero la compra no llegó a confirmarse
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        UUID id = compraDe(detalle);
        assertThat(compras.cambiarEstado(id, EstadoCompra.CONFIRMADA, EstadoCompra.PENDIENTE)).isEqualTo(1);

        avisarCancelacion().andExpect(status().isAccepted());

        Compra compra = compras.findById(id).orElseThrow();
        assertThat(compra.getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        Reintegro r = elReintegroPorCancelacion(id);
        assertThat(r.getMonto()).isEqualByComparingTo(compra.getTotal());
        assertThat(entradasDeReintegro.findByReintegroIdOrderByIdAsc(r.getId()))
                .hasSize(detalle.size()).allMatch(EntradaDeReintegro::isDevuelta);
        assertThat(detalle).allSatisfy(d ->
                assertThat(entradas.estado(d.getEntradaId())).isEqualTo(EstadoEntrada.DEVUELTA));
    }

    @Test
    void unaSolicitudPendienteSeCierraYSuEntradaEntraEnElReintegro() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        UUID devolucionId = devolucionPendiente(CLIENTE, detalle.get(0).getEntradaId());

        avisarCancelacion().andExpect(status().isAccepted());

        SolicitudDevolucion s = solicitudes.findById(devolucionId).orElseThrow();
        assertThat(s.getEstado()).isEqualTo(EstadoSolicitud.RECHAZADA);
        assertThat(s.getMotivoRechazo()).isEqualTo("Evento cancelado: reintegro automático");
        assertThat(s.getResueltaPor()).isEqualTo("sistema");
        Reintegro r = elReintegroPorCancelacion(compraDe(detalle));
        assertThat(r.getMonto()).isEqualByComparingTo(TOTAL);
        assertThat(entradasDeReintegro.findByReintegroIdOrderByIdAsc(r.getId()))
                .extracting(EntradaDeReintegro::getEntradaId).contains(detalle.get(0).getEntradaId());

        // Un administrador ya no puede aprobarla
        aprobar(ADMIN, "ADMIN", devolucionId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DEVOLUCION_YA_RESUELTA"));
    }

    @Test
    void unProcesamientoRepetidoNoRepiteLaMarcacionNiPideOtroReintegro() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        avisarCancelacion().andExpect(status().isAccepted());
        assertThat(cancelacion().isMarcacionHecha()).isTrue();

        cancelacionService.procesar(EVENTO_ID);

        // La marcación ya se había hecho con éxito: no se vuelve a pedir a Entradas
        assertThat(entradas.marcaciones()).isEqualTo(1);
        assertThat(cancelacion().getEntradasMarcadas()).isEqualTo(4);
        assertThat(reintegrosPorCancelacion(compraDe(detalle))).hasSize(1);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
    }

    @Test
    void unaMarcacionFallidaSeVuelveAPedirAlRetomarYLuegoNoSeRepite() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        entradas.setMarcacionCaida(true);
        avisarCancelacion().andExpect(status().isAccepted());
        assertThat(cancelacion().isMarcacionHecha()).isFalse();
        // Los reintegros no dependen de la marcación: la compra ya recibió el suyo
        assertThat(reintegrosPorCancelacion(compraDe(detalle))).hasSize(1);

        entradas.setMarcacionCaida(false);
        cancelacionService.procesar(EVENTO_ID);

        assertThat(entradas.marcaciones()).isEqualTo(2);
        assertThat(cancelacion().isMarcacionHecha()).isTrue();

        cancelacionService.procesar(EVENTO_ID);
        assertThat(entradas.marcaciones()).isEqualTo(2);
        assertThat(reintegrosPorCancelacion(compraDe(detalle))).hasSize(1);
    }

    @Test
    void unReintegroSinRespuestaSeVuelveAPedirConLaMismaClaveAlRetomar() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        pagos.setProximosReintegrosSinRespuesta(1);
        avisarCancelacion().andExpect(status().isAccepted());
        Reintegro pendiente = elReintegroPorCancelacion(compraDe(detalle));
        assertThat(pendiente.getEstado()).isEqualTo(EstadoReintegro.PENDIENTE);
        assertThat(pendiente.getUltimoError()).isNotBlank();

        cancelacionService.procesar(EVENTO_ID);

        Reintegro r = elReintegroPorCancelacion(compraDe(detalle));
        assertThat(r.getRefundRequestId()).isEqualTo(pendiente.getRefundRequestId());
        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
    }

    @Test
    void siEntradasNoMarcaLasEntradasLosReintegrosSiguenIgual() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        entradas.setMarcacionCaida(true);

        avisarCancelacion().andExpect(status().isAccepted());

        assertThat(elReintegroPorCancelacion(compraDe(detalle)).getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(detalle).allSatisfy(d ->
                assertThat(entradas.estado(d.getEntradaId())).isEqualTo(EstadoEntrada.DEVUELTA));
        assertThat(cancelacion().getUltimoError()).startsWith("MARCACION");
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.PROCESADA);
    }

    // ------------------------------------------------------------------ una compra no frena a las demás (4.2)

    @Test
    void unReintegroQueFallaNoFrenaALasDemasCompras() throws Exception {
        List<DetalleCompra> primera = entradasConfirmadas(CLIENTE);
        List<DetalleCompra> segunda = entradasConfirmadas("cliente-2");
        pagos.setProximosReintegrosSinRespuesta(1);

        avisarCancelacion().andExpect(status().isAccepted());

        Reintegro r1 = elReintegroPorCancelacion(compraDe(primera));
        Reintegro r2 = elReintegroPorCancelacion(compraDe(segunda));
        assertThat(List.of(r1.getEstado(), r2.getEstado()))
                .containsExactlyInAnyOrder(EstadoReintegro.PENDIENTE, EstadoReintegro.EXITOSO);
        Reintegro sinRespuesta = r1.getEstado() == EstadoReintegro.PENDIENTE ? r1 : r2;
        assertThat(sinRespuesta.getUltimoError()).isNotBlank();
        assertThat(entradasDeReintegro.findByReintegroIdOrderByIdAsc(sinRespuesta.getId()))
                .noneMatch(EntradaDeReintegro::isDevuelta);
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.PROCESADA);
    }

    @Test
    void unReintegroFallidoQuedaRegistradoYLasDemasComprasSeProcesan() throws Exception {
        List<DetalleCompra> primera = entradasConfirmadas(CLIENTE);
        List<DetalleCompra> segunda = entradasConfirmadas("cliente-2");
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);

        avisarCancelacion().andExpect(status().isAccepted());

        assertThat(elReintegroPorCancelacion(compraDe(primera)).getEstado()).isEqualTo(EstadoReintegro.FALLIDO);
        assertThat(elReintegroPorCancelacion(compraDe(segunda)).getEstado()).isEqualTo(EstadoReintegro.FALLIDO);
        assertThat(cancelacion().getEstado()).isEqualTo(EstadoCancelacion.PROCESADA);
    }

    // ------------------------------------------------------------------ reintento (5.1)

    @Test
    void reintentarUnReintegroPorCancelacionFallidoDevuelveLasEntradas() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);
        avisarCancelacion().andExpect(status().isAccepted());
        Reintegro fallido = elReintegroPorCancelacion(compraDe(detalle));
        assertThat(detalle).allSatisfy(d ->
                assertThat(entradas.estado(d.getEntradaId())).isEqualTo(EstadoEntrada.EN_REINTEGRO));

        reintentarReintegro(fallido.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EXITOSO"));

        Reintegro r = reintegros.findById(fallido.getId()).orElseThrow();
        assertThat(r.getRefundRequestId()).isEqualTo(fallido.getRefundRequestId());
        assertThat(pagos.reintentosDeReintegro()).isEqualTo(1);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
        assertThat(detalle).allSatisfy(d ->
                assertThat(entradas.estado(d.getEntradaId())).isEqualTo(EstadoEntrada.DEVUELTA));
        assertThat(entradasDeReintegro.findByReintegroIdOrderByIdAsc(r.getId())).allMatch(EntradaDeReintegro::isDevuelta);
    }

    @Test
    void unReintentoQueVuelveAFallarResponde202() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);
        pagos.setResultadoReintento(EstadoReintegroPagos.FALLIDO);
        avisarCancelacion().andExpect(status().isAccepted());
        Reintegro fallido = elReintegroPorCancelacion(compraDe(detalle));

        reintentarReintegro(fallido.getId())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.estado").value("FALLIDO"));

        assertThat(detalle).allSatisfy(d ->
                assertThat(entradas.estado(d.getEntradaId())).isEqualTo(EstadoEntrada.EN_REINTEGRO));
    }

    @Test
    void unaTransicionInvalidaADevueltaQuedaRegistradaComoConflictoYSeRetomaSinOtroReintegro() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        String enConflicto = detalle.get(2).getEntradaId();
        entradas.rechazarTransicion(enConflicto);

        avisarCancelacion().andExpect(status().isAccepted());

        Reintegro r = elReintegroPorCancelacion(compraDe(detalle));
        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(r.getUltimoError()).startsWith("ENTRADA_DEVUELTA")
                .contains("Conflicto de estado").contains(enConflicto).doesNotContain("no responde");
        var registradas = entradasDeReintegro.findByReintegroIdOrderByIdAsc(r.getId());
        assertThat(registradas.get(0).isDevuelta()).isTrue();
        assertThat(registradas.get(1).isDevuelta()).isTrue();
        assertThat(registradas.get(2).isDevuelta()).isFalse();
        assertThat(entradas.estado(enConflicto)).isEqualTo(EstadoEntrada.EN_REINTEGRO);

        entradas.aceptarTransicion(enConflicto);
        reintentarReintegro(r.getId()).andExpect(status().isOk());

        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
        assertThat(pagos.reintentosDeReintegro()).isZero();
        assertThat(reintegros.findById(r.getId()).orElseThrow().getUltimoError()).isNull();
        assertThat(detalle).allSatisfy(d ->
                assertThat(entradas.estado(d.getEntradaId())).isEqualTo(EstadoEntrada.DEVUELTA));
        assertThat(entradasDeReintegro.findByReintegroIdOrderByIdAsc(r.getId())).allMatch(EntradaDeReintegro::isDevuelta);
    }

    @Test
    void siEntradasNoRespondeAlDevolverElReintentoTerminaElPaso() throws Exception {
        List<DetalleCompra> detalle = entradasConfirmadas(CLIENTE);
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);
        avisarCancelacion().andExpect(status().isAccepted());
        Reintegro fallido = elReintegroPorCancelacion(compraDe(detalle));

        entradas.setCaido(true);
        reintentarReintegro(fallido.getId()).andExpect(status().isAccepted());
        Reintegro exitoso = reintegros.findById(fallido.getId()).orElseThrow();
        assertThat(exitoso.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(exitoso.getUltimoError()).startsWith("ENTRADA_DEVUELTA");

        entradas.setCaido(false);
        reintentarReintegro(fallido.getId()).andExpect(status().isOk());

        assertThat(pagos.reintentosDeReintegro()).isEqualTo(1);
        assertThat(reintegros.findById(fallido.getId()).orElseThrow().getUltimoError()).isNull();
        assertThat(detalle).allSatisfy(d ->
                assertThat(entradas.estado(d.getEntradaId())).isEqualTo(EstadoEntrada.DEVUELTA));
    }
}
