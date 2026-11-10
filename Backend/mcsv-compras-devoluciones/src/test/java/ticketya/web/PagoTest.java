package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import ticketya.PruebaIntegracion;
import ticketya.cliente.PagosCliente.EstadoPago;
import ticketya.cliente.PagosCliente.Pago;
import ticketya.cliente.ReservasCliente.EstadoReserva;
import ticketya.modelo.ClaveIdempotencia;
import ticketya.modelo.ClaveIdempotenciaId;
import ticketya.modelo.Compra;
import ticketya.modelo.EstadoClave;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;
import ticketya.cliente.PagosCliente.EstadoReintegroPagos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** C-3 (pago aprobado), C-4 (pago rechazado) y C-5 (reintento idempotente). */
class PagoTest extends PruebaIntegracion {

    private static final String CLIENTE = "cliente-1";

    // ------------------------------------------------------------------ C-3

    @Test
    void pagoAprobadoConfirmaLaVentaEmiteLasEntradasYConfirmaLaCompra() throws Exception {
        UUID id = compraPendiente(CLIENTE);

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"))
                .andExpect(jsonPath("$.medioDePago").value("CREDIT_CARD"))
                .andExpect(jsonPath("$.lugares[0].entradaId").isNotEmpty())
                .andExpect(jsonPath("$.lugares[3].entradaId").isNotEmpty());

        Compra compra = compras.buscarConDetalle(id).orElseThrow();
        assertThat(compra.getEstado()).isEqualTo(EstadoCompra.CONFIRMADA);
        assertThat(compra.getPagoId()).isNotNull();
        assertThat(compra.isVentaConfirmada()).isTrue();
        assertThat(compra.getDetalle()).allSatisfy(d -> assertThat(d.getEntradaId()).isNotNull());
        assertThat(compra.getDetalle()).extracting("entradaId").doesNotHaveDuplicates();
        assertThat(reservas.reserva(compra.getReservaId()).estado()).isEqualTo(EstadoReserva.CONFIRMADA);
        assertThat(reservas.confirmaciones()).isEqualTo(1);
        assertThat(entradas.emisiones()).isEqualTo(1);
        assertThat(pagos.cobrosNuevos()).isEqualTo(1);
    }

    @Test
    void elCobroUsaElCompraIdComoPurchaseIdYUnaClaveDeCobroPropia() throws Exception {
        UUID id = compraPendiente(CLIENTE);

        pagar(CLIENTE, id, "clave-1", "DEBIT_CARD").andExpect(status().isOk());

        Compra compra = compras.findById(id).orElseThrow();
        ClaveIdempotencia clave = claves.findById(new ClaveIdempotenciaId(CLIENTE, "clave-1")).orElseThrow();
        assertThat(clave.getClaveCobro()).isNotEqualTo("clave-1");
        Pago pago = pagos.pagoConClave(clave.getClaveCobro()).orElseThrow();
        assertThat(pago.purchaseId()).isEqualTo(id.toString());
        assertThat(pago.amount()).isEqualByComparingTo(compra.getTotal());
        assertThat(pago.paymentMethod()).isEqualTo("DEBIT_CARD");
        assertThat(compra.getPagoId()).isEqualTo(pago.paymentId());
    }

    @Test
    void confirmacionRechazadaDejaLaCompraFallidaConUnReintegroAutomaticoPorElTotal() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        reservas.rechazarConfirmacion("RESERVA_VENCIDA");

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FALLIDA"));

        Compra compra = compras.findById(id).orElseThrow();
        assertThat(compra.getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        assertThat(compra.getPagoId()).isNotNull();
        assertThat(entradas.llamadas()).isZero();
        assertThat(reservas.liberaciones()).isZero();
        Reintegro r = reintegros.findByCompraId(id).get(0);
        assertThat(r.getOrigen()).isEqualTo(OrigenReintegro.VENTA_RECHAZADA);
        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(r.getMonto()).isEqualByComparingTo(compra.getTotal());
        assertThat(r.getRefundRequestId()).isNotEqualTo(id);
        var enPagos = pagos.reintegroPorSolicitud(r.getRefundRequestId().toString()).orElseThrow();
        assertThat(enPagos.paymentId()).isEqualTo(compra.getPagoId());
        assertThat(enPagos.amount()).isEqualByComparingTo(compra.getTotal());
        assertThat(enPagos.reason()).isNotBlank();
    }

    @Test
    void siElReintegroAutomaticoFallaLaCompraQuedaFallidaIgual() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        reservas.rechazarConfirmacion("RESERVA_LIBERADA");
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FALLIDA"));

        assertThat(compras.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        assertThat(reintegros.findByCompraId(id)).singleElement()
                .satisfies(r -> assertThat(r.getEstado()).isEqualTo(EstadoReintegro.FALLIDO));
    }

    @Test
    void siElReintegroAutomaticoNoTieneRespuestaQuedaPendienteParaReintentarlo() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        reservas.rechazarConfirmacion("RESERVA_VENCIDA");
        pagos.setReintegrosCaidos(true);

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD").andExpect(status().isOk());

        assertThat(compras.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        assertThat(reintegros.findByCompraId(id)).singleElement().satisfies(r -> {
            assertThat(r.getEstado()).isEqualTo(EstadoReintegro.PENDIENTE);
            assertThat(r.getUltimoError()).isNotBlank();
        });
    }

    @Test
    void repetirElPagoTrasUnaVentaRechazadaNoCobraNiReintegraDeNuevo() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        reservas.rechazarConfirmacion("RESERVA_VENCIDA");
        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD").andExpect(status().isOk());

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("FALLIDA"));
        pagar(CLIENTE, id, "clave-2", "CREDIT_CARD")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("FALLIDA"));

        assertThat(pagos.pedidosDeCobro()).isEqualTo(1);
        assertThat(pagos.pedidosDeReintegro()).isEqualTo(1);
        assertThat(reintegros.findByCompraId(id)).hasSize(1);
    }

    @Test
    void emisionFallidaSeReintentaTresVecesYLaCompraQuedaPendienteSinVolverACobrar() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        entradas.fallarProximas(3);

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"));

        assertThat(entradas.llamadas()).isEqualTo(3);
        Compra compra = compras.findById(id).orElseThrow();
        assertThat(compra.getEstado()).isEqualTo(EstadoCompra.PENDIENTE);
        assertThat(compra.getPagoId()).isNotNull();
        assertThat(pagos.cobrosNuevos()).isEqualTo(1);
    }

    @Test
    void repetirElPagoTrasUnaEmisionFallidaSoloRepiteLaEmision() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        entradas.fallarProximas(3);
        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD").andExpect(status().isAccepted());

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));

        assertThat(pagos.pedidosDeCobro()).isEqualTo(1);
        assertThat(reservas.confirmaciones()).isEqualTo(1);
        assertThat(entradas.llamadas()).isEqualTo(4);
        assertThat(compras.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoCompra.CONFIRMADA);
    }

    @Test
    void unPagoRetomadoNoReabreUnaCompraQueDejoDeEstarPendiente() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        entradas.fallarProximas(3);
        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD").andExpect(status().isAccepted());
        int cobros = pagos.pedidosDeCobro();
        int confirmaciones = reservas.confirmaciones();
        int emisiones = entradas.llamadas();
        // La compra se cierra por fuera del pago (como lo hace la cancelación del evento)
        assertThat(compras.cambiarEstado(id, EstadoCompra.PENDIENTE, EstadoCompra.FALLIDA)).isEqualTo(1);

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FALLIDA"));

        assertThat(pagos.pedidosDeCobro()).isEqualTo(cobros);
        assertThat(reservas.confirmaciones()).isEqualTo(confirmaciones);
        assertThat(entradas.llamadas()).isEqualTo(emisiones);
        assertThat(compras.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoCompra.FALLIDA);
    }

    @Test
    void unPagoConClaveNuevaSobreUnaCompraCerradaSinCobroNoLlamaAPagos() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        assertThat(compras.cambiarEstado(id, EstadoCompra.PENDIENTE, EstadoCompra.FALLIDA)).isEqualTo(1);

        pagar(CLIENTE, id, "clave-nueva", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FALLIDA"));

        assertThat(pagos.pedidosDeCobro()).isZero();
        assertThat(pagos.consultasPorCompra()).isZero();
        assertThat(reservas.confirmaciones()).isZero();
    }

    @Test
    void unSectorNoNumeradoPideUnaLineaPorLugarYCadaLugarRecibeSuEntrada() throws Exception {
        String reservaId = reserva(CLIENTE, ticketya.cliente.ReservasCliente.EstadoReserva.VIGENTE, List.of(
                new ticketya.cliente.ReservasCliente.DetalleReserva(CAMPO, null, null, 2)));
        iniciarCompra(CLIENTE, reservaId);
        UUID id = compras.findByReservaId(reservaId).orElseThrow().getId();

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));

        var pedido = entradas.pedidosDeEmision().getFirst();
        assertThat(pedido.lugares()).hasSize(2).allSatisfy(l -> {
            assertThat(l.idSector()).isEqualTo(CAMPO);
            assertThat(l.nombreSector()).isEqualTo("Campo");
            assertThat(l.fila()).isNull();
            assertThat(l.numero()).isNull();
        });
        Compra compra = compras.buscarConDetalle(id).orElseThrow();
        assertThat(compra.getDetalle()).hasSize(2).allSatisfy(d -> assertThat(d.getEntradaId()).isNotNull());
        assertThat(compra.getDetalle()).extracting("entradaId").doesNotHaveDuplicates();
    }

    @Test
    void elReintentoDeLaEmisionEnviaElMismoPedido() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        entradas.fallarProximas(1);

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD").andExpect(status().isOk());

        var pedidos = entradas.pedidosDeEmision();
        assertThat(pedidos).hasSize(2);
        assertThat(pedidos.get(1)).isEqualTo(pedidos.get(0));
        assertThat(pedidos.get(0).idCompra()).isEqualTo(id.toString());
        assertThat(entradas.emisiones()).isEqualTo(1);
    }

    @Test
    void unaCompraConPagoAprobadoNoSeCobraDeNuevoAunqueCambieLaClave() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD").andExpect(status().isOk());

        pagar(CLIENTE, id, "clave-2", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));

        assertThat(pagos.pedidosDeCobro()).isEqualTo(1);
        assertThat(entradas.llamadas()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ C-2 de punta a punta

    @Test
    void conLaReservaVencidaNoSePideElCobro() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        reservas.cambiarEstado(compras.findById(id).orElseThrow().getReservaId(), EstadoReserva.LIBERADA);

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RESERVA_NO_VIGENTE"));

        assertThat(pagos.pedidosDeCobro()).isZero();
    }

    // ------------------------------------------------------------------ C-4

    @Test
    void pagoRechazadoDejaLaCompraFallidaYLiberaLosLugares() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        pagos.setDecision(EstadoPago.RECHAZADO);

        pagar(CLIENTE, id, "clave-1", "DIGITAL_WALLET")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FALLIDA"))
                .andExpect(jsonPath("$.lugares[0].entradaId").doesNotExist());

        Compra compra = compras.findById(id).orElseThrow();
        assertThat(compra.getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        assertThat(compra.getPagoId()).isNull();
        assertThat(reservas.liberaciones()).isEqualTo(1);
        assertThat(reservas.reserva(compra.getReservaId()).estado()).isEqualTo(EstadoReserva.LIBERADA);
        assertThat(reservas.confirmaciones()).isZero();
        assertThat(entradas.llamadas()).isZero();
    }

    @Test
    void siNoSePuedeLiberarLaReservaLaCompraIgualQuedaFallida() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        pagos.setDecision(EstadoPago.RECHAZADO);
        reservas.rechazarConfirmacion("NO_DEBERIA_LLAMARSE");

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD").andExpect(status().isOk());

        assertThat(compras.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoCompra.FALLIDA);
    }

    // ------------------------------------------------------------------ validaciones (6.1, 6.2)

    @Test
    void medioDePagoNoAdmitidoDa400SinLlamarAPagos() throws Exception {
        UUID id = compraPendiente(CLIENTE);

        pagar(CLIENTE, id, "clave-1", "EFECTIVO")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MEDIO_DE_PAGO_INVALIDO"));

        assertThat(pagos.pedidosDeCobro()).isZero();
        assertThat(claves.count()).isZero();
    }

    @Test
    void sinIdempotencyKeyDa400() throws Exception {
        UUID id = compraPendiente(CLIENTE);

        pagar(CLIENTE, id, null, "CREDIT_CARD")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CLAVE_IDEMPOTENCIA_INVALIDA"));

        assertThat(pagos.pedidosDeCobro()).isZero();
    }

    @Test
    void noSePuedePagarLaCompraDeOtroCliente() throws Exception {
        UUID id = compraPendiente(CLIENTE);

        pagar("cliente-2", id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESO_DENEGADO"));

        assertThat(pagos.pedidosDeCobro()).isZero();
    }

    @Test
    void pagarUnaCompraInexistenteDa404() throws Exception {
        pagar(CLIENTE, UUID.randomUUID(), "clave-1", "CREDIT_CARD")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("COMPRA_NO_ENCONTRADA"));
    }

    // ------------------------------------------------------------------ C-5

    @Test
    void reintentoConLaMismaClaveDevuelveElResultadoOriginalSinCobrarNiEmitir() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        String primera = pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String segunda = pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(segunda).isEqualTo(primera);
        assertThat(pagos.pedidosDeCobro()).isEqualTo(1);
        assertThat(entradas.llamadas()).isEqualTo(1);
    }

    @Test
    void elReintentoDeUnRechazoDevuelveElRechazoOriginal() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        pagos.setDecision(EstadoPago.RECHAZADO);
        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD").andExpect(status().isOk());

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FALLIDA"));

        assertThat(pagos.pedidosDeCobro()).isEqualTo(1);
        assertThat(reservas.liberaciones()).isEqualTo(1);
    }

    @Test
    void mismaClaveConDatosDistintosDa409() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD").andExpect(status().isOk());

        pagar(CLIENTE, id, "clave-1", "DEBIT_CARD")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCIA_CONFLICTO"));

        assertThat(pagos.pedidosDeCobro()).isEqualTo(1);
    }

    @Test
    void laMismaClaveEnDosClientesDistintosSeTrataPorSeparado() throws Exception {
        UUID compra1 = compraPendiente("cliente-1");
        UUID compra2 = compraPendiente("cliente-2");

        pagar("cliente-1", compra1, "1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(compra1.toString()));
        pagar("cliente-2", compra2, "1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(compra2.toString()));

        assertThat(pagos.cobrosNuevos()).isEqualTo(2);
        assertThat(compras.findById(compra2).orElseThrow().getEstado()).isEqualTo(EstadoCompra.CONFIRMADA);
    }

    @Test
    void unReintentoMientrasElPrimerPagoEstaEnProcesoRecibeEnCurso() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        pagos.setDemoraMs(1500);

        List<MvcResult> resultados = alMismoTiempo(
                () -> pagar(CLIENTE, id, "clave-1", "CREDIT_CARD").andReturn(),
                () -> {
                    Thread.sleep(500);
                    return pagar(CLIENTE, id, "clave-1", "CREDIT_CARD").andReturn();
                });

        assertThat(resultados).extracting(r -> r.getResponse().getStatus()).containsExactlyInAnyOrder(200, 202);
        MvcResult enCurso = resultados.stream().filter(r -> r.getResponse().getStatus() == 202).findFirst().orElseThrow();
        assertThat(enCurso.getResponse().getContentAsString()).contains("EN_CURSO");
        assertThat(pagos.pedidosDeCobro()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 6.10 cobro incierto

    @Test
    void siElCobroSeHizoPeroNoLlegoLaRespuestaAlRepetirSeUsaEsePago() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        pagos.setPerderRespuesta(true);

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICIO_NO_DISPONIBLE"));
        assertThat(compras.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoCompra.PENDIENTE);
        assertThat(claves.findById(new ClaveIdempotenciaId(CLIENTE, "clave-1")).orElseThrow().getEstado())
                .isEqualTo(EstadoClave.INTERRUMPIDA);

        pagos.setPerderRespuesta(false);
        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));

        assertThat(pagos.consultasPorCompra()).isEqualTo(1);
        assertThat(pagos.pedidosDeCobro()).isEqualTo(1);
        assertThat(pagos.cobrosNuevos()).isEqualTo(1);
    }

    @Test
    void siElCobroNuncaLlegoAPagosAlRepetirSeCobraConLaMismaClaveDeCobro() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        pagos.setCaido(true);
        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD").andExpect(status().isServiceUnavailable());

        pagos.setCaido(false);
        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));

        String claveCobro = claves.findById(new ClaveIdempotenciaId(CLIENTE, "clave-1")).orElseThrow().getClaveCobro();
        assertThat(pagos.pagoConClave(claveCobro)).isPresent();
        assertThat(pagos.consultasPorCompra()).isEqualTo(1);
        assertThat(pagos.cobrosNuevos()).isEqualTo(1);
    }

    @Test
    void siElCobroRechazadoNoDevolvioRespuestaAlRepetirSeRecibeElRechazoOriginal() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        pagos.setDecision(EstadoPago.RECHAZADO);
        pagos.setPerderRespuesta(true);
        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD").andExpect(status().isServiceUnavailable());

        pagos.setPerderRespuesta(false);
        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FALLIDA"));

        // La lista solo tenía un rechazo: se repitió el cobro con la misma claveCobro
        assertThat(pagos.consultasPorCompra()).isEqualTo(1);
        assertThat(pagos.pedidosDeCobro()).isEqualTo(2);
        assertThat(pagos.cobrosNuevos()).isEqualTo(1);
        assertThat(reservas.liberaciones()).isEqualTo(1);
        assertThat(reservas.confirmaciones()).isZero();
        assertThat(compras.findById(id).orElseThrow().getPagoId()).isNull();
    }

    @Test
    void siPagosRespondeConflictoSeUsaElPagoAprobadoDeLaCompra() throws Exception {
        UUID id = compraPendiente(CLIENTE);
        Compra compra = compras.findById(id).orElseThrow();
        pagos.registrarPago(id.toString(), compra.getTotal(), EstadoPago.RECHAZADO);
        Pago aprobado = pagos.registrarPago(id.toString(), compra.getTotal(), EstadoPago.APROBADO);

        pagar(CLIENTE, id, "clave-1", "CREDIT_CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));

        assertThat(compras.findById(id).orElseThrow().getPagoId()).isEqualTo(aprobado.paymentId());
        assertThat(pagos.cobrosNuevos()).isZero();
    }

    @Test
    void laBilleteraDigitalSiempreSeRechaza() throws Exception {
        UUID id = compraPendiente(CLIENTE);

        pagar(CLIENTE, id, "clave-1", "DIGITAL_WALLET")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FALLIDA"));

        assertThat(reservas.liberaciones()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ utilidades

    @SafeVarargs
    static <T> List<T> alMismoTiempo(Callable<T>... tareas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tareas.length);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<T>> futuros = new ArrayList<>();
        for (Callable<T> tarea : tareas) {
            futuros.add(pool.submit(() -> {
                largada.await();
                return tarea.call();
            }));
        }
        largada.countDown();
        List<T> resultados = new ArrayList<>();
        for (Future<T> f : futuros) {
            resultados.add(f.get());
        }
        pool.shutdown();
        return resultados;
    }
}
