package ticketya.servicio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ticketya.PruebaIntegracion;
import ticketya.cliente.PagosCliente.EstadoPago;
import ticketya.cliente.PagosCliente.EstadoReintegroPagos;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pedido y reintento de reintegros contra el doble de Pagos. */
class ReintegroServiceTest extends PruebaIntegracion {

    @Autowired
    ReintegroService servicio;

    private final UUID compraId = UUID.randomUUID();
    private String pagoId;

    @BeforeEach
    void pagoAprobado() {
        pagoId = pagos.registrarPago(compraId.toString(), new BigDecimal("46001.00"), EstadoPago.APROBADO).paymentId();
    }

    private Reintegro registrado(String monto) {
        return servicio.registrar(compraId, UUID.randomUUID(), new BigDecimal(monto),
                "Devolución de entrada e-1", OrigenReintegro.DEVOLUCION);
    }

    // ------------------------------------------------------------------ 3.1 pedir

    @Test
    void unReintegroNaceRegistradoComoPendienteAntesDePedirlo() {
        Reintegro r = registrado("15000.00");

        assertThat(reintegros.findById(r.getId()).orElseThrow().getEstado()).isEqualTo(EstadoReintegro.PENDIENTE);
        assertThat(pagos.pedidosDeReintegro()).isZero();
    }

    @Test
    void conRespuestaExitosaQuedaExitosoConElIdDePagosYLaClaveEsElRefundRequestId() {
        Reintegro r = servicio.pedir(registrado("15000.00"), pagoId);

        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(r.getPagosReintegroId()).isNotNull();
        assertThat(r.getIntentos()).isEqualTo(1);
        var enPagos = pagos.reintegroPorSolicitud(r.getRefundRequestId().toString()).orElseThrow();
        assertThat(enPagos.reason()).isEqualTo("Devolución de entrada e-1");
        assertThat(enPagos.amount()).isEqualByComparingTo("15000.00");
        // La clave enviada es el mismo refundRequestId: repetir con ese valor no da conflicto
        assertThat(pagos.reintegrar(pagoId, r.getRefundRequestId().toString(), r.getRefundRequestId().toString(),
                r.getMonto(), r.getMotivo()).id()).isEqualTo(r.getPagosReintegroId());
    }

    @Test
    void conRespuestaFallidaQuedaFallido() {
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);

        Reintegro r = servicio.pedir(registrado("15000.00"), pagoId);

        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.FALLIDO);
        assertThat(r.getPagosReintegroId()).isNotNull();
        assertThat(r.getUltimoError()).isNotBlank();
    }

    @Test
    void sinRespuestaQuedaPendienteConElError() {
        pagos.setReintegrosCaidos(true);

        Reintegro r = servicio.pedir(registrado("15000.00"), pagoId);

        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.PENDIENTE);
        assertThat(r.getPagosReintegroId()).isNull();
        assertThat(reintegros.findById(r.getId()).orElseThrow().getUltimoError()).contains("no responde");
    }

    @Test
    void siPagosLoRechazaAlPedirloQuedaPendienteConElError() {
        Reintegro r = servicio.pedir(registrado("99999.00"), pagoId);

        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.PENDIENTE);
        assertThat(r.getUltimoError()).contains("supera lo cobrado");
    }

    @Test
    void variosReintegrosParcialesDeUnMismoPago() {
        Reintegro a = servicio.pedir(registrado("15000.00"), pagoId);
        Reintegro b = servicio.pedir(registrado("15000.00"), pagoId);

        assertThat(a.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(b.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 3.2 reintentar

    @Test
    void unPendienteSinRespuestaSeVuelveAPedirConLaMismaClaveSinDuplicar() {
        pagos.setPerderRespuestaReintegro(true);
        Reintegro r = servicio.pedir(registrado("15000.00"), pagoId);
        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.PENDIENTE);
        pagos.setPerderRespuestaReintegro(false);

        r = servicio.reintentar(r, pagoId);

        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
        assertThat(pagos.pedidosDeReintegro()).isEqualTo(2);
        assertThat(pagos.reintentosDeReintegro()).isZero();
    }

    @Test
    void unFallidoUsaElRetryDePagosSobreElMismoReintegro() {
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);
        Reintegro r = servicio.pedir(registrado("15000.00"), pagoId);
        String idEnPagos = r.getPagosReintegroId();
        UUID refundRequestId = r.getRefundRequestId();

        r = servicio.reintentar(r, pagoId);

        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(r.getPagosReintegroId()).isEqualTo(idEnPagos);
        assertThat(r.getRefundRequestId()).isEqualTo(refundRequestId);
        assertThat(r.getIntentos()).isEqualTo(2);
        assertThat(pagos.reintentosDeReintegro()).isEqualTo(1);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
        assertThat(pagos.pedidosDeReintegro()).isEqualTo(1);
    }

    @Test
    void unFallidoQueVuelveAFallarSigueFallido() {
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);
        pagos.setResultadoReintento(EstadoReintegroPagos.FALLIDO);
        Reintegro r = servicio.pedir(registrado("15000.00"), pagoId);

        r = servicio.reintentar(r, pagoId);

        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.FALLIDO);
        assertThat(r.getIntentos()).isEqualTo(2);
    }

    @Test
    void siElRetryDa409SeConsultaElEstadoDelReintegro() {
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);
        Reintegro r = servicio.pedir(registrado("15000.00"), pagoId);
        pagos.setReintentoYaHecho(true);

        r = servicio.reintentar(r, pagoId);

        assertThat(pagos.consultasDeReintegro()).isEqualTo(1);
        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
    }

    @Test
    void unExitosoNoLlamaAPagos() {
        Reintegro r = servicio.pedir(registrado("15000.00"), pagoId);
        int pedidos = pagos.pedidosDeReintegro();

        r = servicio.reintentar(r, pagoId);

        assertThat(r.getEstado()).isEqualTo(EstadoReintegro.EXITOSO);
        assertThat(pagos.pedidosDeReintegro()).isEqualTo(pedidos);
        assertThat(pagos.reintentosDeReintegro()).isZero();
    }

    @Test
    void nuncaSeCreaUnRefundRequestIdNuevo() {
        pagos.setResultadoReintegro(EstadoReintegroPagos.FALLIDO);
        pagos.setResultadoReintento(EstadoReintegroPagos.FALLIDO);
        Reintegro r = servicio.pedir(registrado("15000.00"), pagoId);

        for (int i = 0; i < 3; i++) {
            r = servicio.reintentar(r, pagoId);
        }

        assertThat(reintegros.count()).isEqualTo(1);
        assertThat(pagos.reintegrosNuevos()).isEqualTo(1);
        assertThat(r.getIntentos()).isEqualTo(4);
    }
}
