package ticketya.repositorio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import ticketya.modelo.Compra;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EntradaDeReintegro;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;
import ticketya.modelo.SolicitudDevolucion;
import ticketya.servicio.ReporteService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Consultas agregadas de los reportes para organizadores, sobre la base de prueba. */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/ticketya_compras_test?createDatabaseIfNotExist=true&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "compras.cancelacion.reanudacion-activa=false",
        "compras.avisos.envio-activo=false"
})
class ConsultasDeReportesTest {

    static final String EVENTO = "evento-r";
    static final String OTRO_EVENTO = "evento-otro";

    @Autowired CompraRepository compras;
    @Autowired SolicitudDevolucionRepository solicitudes;
    @Autowired ReintegroRepository reintegros;
    @Autowired EntradaDeReintegroRepository entradasDeReintegro;
    @Autowired AvisoPendienteRepository avisos;
    @Autowired CancelacionEventoRepository cancelaciones;
    @Autowired ModificacionEventoRepository modificaciones;
    @Autowired ClaveIdempotenciaRepository claves;
    @Autowired ReporteService reportes;

    @BeforeEach
    void limpiar() {
        avisos.deleteAll();
        modificaciones.deleteAll();
        cancelaciones.deleteAll();
        entradasDeReintegro.deleteAll();
        solicitudes.deleteAll();
        reintegros.deleteAll();
        claves.deleteAll();
        compras.deleteAll();
    }

    // ------------------------------------------------------------------ datos

    /** Un lugar de la compra: sector, precio histórico y entrada emitida. */
    record Lugar(String sectorId, String precio, String entradaId) {
    }

    static Lugar lugar(String sectorId, String precio) {
        return new Lugar(sectorId, precio, "ent-" + UUID.randomUUID());
    }

    Compra compra(String eventoId, EstadoCompra estado, String pagoId, Lugar... lugares) {
        Compra c = new Compra();
        c.setClienteId("cliente-" + UUID.randomUUID());
        c.setReservaId(UUID.randomUUID().toString());
        c.setEventoId(eventoId);
        c.setEventoNombre("Recital");
        c.setEventoFechaInicio(Instant.parse("2026-12-01T21:00:00Z"));
        c.setEstado(estado);
        c.setPagoId(pagoId);
        BigDecimal total = BigDecimal.ZERO;
        for (Lugar l : lugares) {
            DetalleCompra d = new DetalleCompra();
            d.setSectorId(l.sectorId());
            d.setSectorNombre(l.sectorId().substring(0, 1).toUpperCase() + l.sectorId().substring(1));
            d.setPrecioUnitario(new BigDecimal(l.precio()));
            d.setEntradaId(estado == EstadoCompra.CONFIRMADA ? l.entradaId() : null);
            c.agregarDetalle(d);
            total = total.add(new BigDecimal(l.precio()));
        }
        c.setTotal(total);
        return compras.saveAndFlush(c);
    }

    Compra confirmada(Lugar... lugares) {
        return compra(EVENTO, EstadoCompra.CONFIRMADA, "pago-" + UUID.randomUUID(), lugares);
    }

    DetalleCompra detalle(Compra c, Lugar l) {
        return compras.buscarConDetalle(c.getId()).orElseThrow().getDetalle().stream()
                .filter(d -> l.entradaId().equals(d.getEntradaId())).findFirst().orElseThrow();
    }

    /** Solicitud de devolución del lugar; con aprobada, se aprueba; con devuelta, la entrada quedó devuelta. */
    SolicitudDevolucion solicitud(Compra c, Lugar l, boolean aprobada, boolean devuelta) {
        SolicitudDevolucion s = solicitudes.saveAndFlush(new SolicitudDevolucion(c.getClienteId(), c, detalle(c, l)));
        if (aprobada) {
            solicitudes.aprobar(s.getId(), "admin-1", Instant.now());
            s = solicitudes.findById(s.getId()).orElseThrow();
        }
        if (devuelta) {
            s.setEntradaDevuelta(true);
            s = solicitudes.saveAndFlush(s);
        }
        return s;
    }

    Reintegro reintegro(Compra c, UUID refundRequestId, String monto, OrigenReintegro origen, EstadoReintegro estado) {
        Reintegro r = new Reintegro(c.getId(), refundRequestId, new BigDecimal(monto), "motivo", origen);
        r.setEstado(estado);
        return reintegros.saveAndFlush(r);
    }

    /** Reintegro de una devolución aprobada (refundRequestId = id de la solicitud). */
    Reintegro reintegroDeDevolucion(SolicitudDevolucion s, EstadoReintegro estado) {
        Compra c = compras.findById(s.getCompraId()).orElseThrow();
        return reintegro(c, s.getId(), s.getMonto().toPlainString(), OrigenReintegro.DEVOLUCION, estado);
    }

    /** Reintegro por cancelación del evento con sus entradas (devueltas si el reintegro salió bien). */
    Reintegro reintegroDeCancelacion(Compra c, EstadoReintegro estado, Lugar... lugares) {
        BigDecimal monto = BigDecimal.ZERO;
        for (Lugar l : lugares) {
            monto = monto.add(new BigDecimal(l.precio()));
        }
        Reintegro r = reintegro(c, UUID.randomUUID(), monto.toPlainString(), OrigenReintegro.CANCELACION_EVENTO, estado);
        for (Lugar l : lugares) {
            EntradaDeReintegro e = new EntradaDeReintegro(r.getId(), l.entradaId());
            e.setDevuelta(estado == EstadoReintegro.EXITOSO);
            entradasDeReintegro.saveAndFlush(e);
        }
        return r;
    }

    // ------------------------------------------------------------------ operaciones pendientes

    @Test
    void sinPendientesNoHayOperacionesPendientes() {
        confirmada(lugar("platea", "10000.00"));
        compra(EVENTO, EstadoCompra.FALLIDA, null, lugar("campo", "5000.00"));

        assertThat(reportes.hayOperacionesPendientes(EVENTO)).isFalse();
    }

    @Test
    void unaCompraPendienteSinPagoNoEsUnaOperacionPendiente() {
        confirmada(lugar("platea", "10000.00"));
        compra(EVENTO, EstadoCompra.PENDIENTE, null, lugar("platea", "10000.00"));

        assertThat(compras.contarCobradasSinConfirmar(EVENTO)).isZero();
        assertThat(reportes.hayOperacionesPendientes(EVENTO)).isFalse();
    }

    @Test
    void unaCompraPendienteCobradaEsUnaOperacionPendiente() {
        compra(EVENTO, EstadoCompra.PENDIENTE, "pago-1", lugar("platea", "10000.00"));

        assertThat(compras.contarCobradasSinConfirmar(EVENTO)).isEqualTo(1);
        assertThat(reportes.hayOperacionesPendientes(EVENTO)).isTrue();
    }

    @Test
    void unaDevolucionEnCursoEsUnaOperacionPendiente() {
        Lugar l = lugar("platea", "10000.00");
        solicitud(confirmada(l), l, true, false);

        assertThat(solicitudes.contarEnCurso(EVENTO)).isEqualTo(1);
        assertThat(reportes.hayOperacionesPendientes(EVENTO)).isTrue();
    }

    @Test
    void unaDevolucionCompletadaConReintegroExitosoNoEsPendiente() {
        Lugar l = lugar("platea", "10000.00");
        SolicitudDevolucion s = solicitud(confirmada(l), l, true, true);
        reintegroDeDevolucion(s, EstadoReintegro.EXITOSO);

        assertThat(reportes.hayOperacionesPendientes(EVENTO)).isFalse();
    }

    @Test
    void unReintegroFallidoEsUnaOperacionPendiente() {
        Lugar l = lugar("platea", "10000.00");
        reintegroDeCancelacion(confirmada(l), EstadoReintegro.FALLIDO, l);

        assertThat(reintegros.contarSinExito(EVENTO)).isEqualTo(1);
        assertThat(reportes.hayOperacionesPendientes(EVENTO)).isTrue();
    }

    @Test
    void lasOperacionesDeOtroEventoNoCuentan() {
        compra(OTRO_EVENTO, EstadoCompra.PENDIENTE, "pago-1", lugar("platea", "10000.00"));

        assertThat(reportes.hayOperacionesPendientes(EVENTO)).isFalse();
    }

    // ------------------------------------------------------------------ ventas

    static BigDecimal pesos(String importe) {
        return new BigDecimal(importe);
    }

    @Test
    void lasVentasSeAgrupanPorSectorConLosPreciosHistoricos() {
        confirmada(lugar("platea", "10000.00"), lugar("platea", "10000.00"));
        confirmada(lugar("campo", "5000.00"));
        // Una venta posterior con otro precio no cambia lo ya vendido
        confirmada(lugar("platea", "12000.00"));

        assertThat(compras.ventasPorSector(EVENTO))
                .extracting(TotalPorSector::sectorId, TotalPorSector::sectorNombre, TotalPorSector::cantidad)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("campo", "Campo", 1L),
                        org.assertj.core.groups.Tuple.tuple("platea", "Platea", 3L));
        assertThat(compras.ventasPorSector(EVENTO))
                .extracting(TotalPorSector::importe)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(pesos("5000"), pesos("32000"));
        assertThat(compras.countByEventoIdAndEstado(EVENTO, EstadoCompra.CONFIRMADA)).isEqualTo(3);
    }

    @Test
    void lasComprasPendientesYFallidasNoSonVentas() {
        compra(EVENTO, EstadoCompra.PENDIENTE, "pago-1", lugar("platea", "10000.00"));
        compra(EVENTO, EstadoCompra.PENDIENTE, null, lugar("platea", "10000.00"));
        compra(EVENTO, EstadoCompra.FALLIDA, "pago-2", lugar("campo", "5000.00"));
        compra(OTRO_EVENTO, EstadoCompra.CONFIRMADA, "pago-3", lugar("platea", "10000.00"));

        assertThat(compras.ventasPorSector(EVENTO)).isEmpty();
        assertThat(compras.countByEventoIdAndEstado(EVENTO, EstadoCompra.CONFIRMADA)).isZero();
        CantidadEImporte sinConfirmar = compras.cobradasSinConfirmar(EVENTO);
        assertThat(sinConfirmar.cantidad()).isEqualTo(1);
        assertThat(sinConfirmar.importe()).isEqualByComparingTo("10000");
        CantidadEImporte noConfirmadas = compras.cobradasNoConfirmadas(EVENTO);
        assertThat(noConfirmadas.cantidad()).as("pendiente cobrada y fallida cobrada").isEqualTo(2);
        assertThat(noConfirmadas.importe()).isEqualByComparingTo("15000");
    }

    @Test
    void sinComprasCobradasLosImportesSonCero() {
        assertThat(compras.cobradasSinConfirmar(EVENTO).cantidad()).isZero();
        assertThat(compras.cobradasSinConfirmar(EVENTO).importe()).isEqualByComparingTo("0");
    }

    @Test
    void unaEntradaGratuitaCuentaComoVendidaConImporteCero() {
        confirmada(lugar("campo", "0.00"), lugar("campo", "5000.00"));

        TotalPorSector campo = compras.ventasPorSector(EVENTO).get(0);
        assertThat(campo.cantidad()).isEqualTo(2);
        assertThat(campo.importe()).isEqualByComparingTo("5000");
    }

    // ------------------------------------------------------------------ devoluciones

    @Test
    void soloLasDevolucionesCompletadasCuentanComoDevueltas() {
        Lugar completada = lugar("platea", "10000.00");
        Lugar aprobadaSinTerminar = lugar("platea", "10000.00");
        Lugar pendiente = lugar("campo", "5000.00");
        Lugar rechazada = lugar("campo", "5000.00");
        Compra c = confirmada(completada, aprobadaSinTerminar, pendiente, rechazada);
        solicitud(c, completada, true, true);
        solicitud(c, aprobadaSinTerminar, true, false);
        solicitud(c, pendiente, false, false);
        SolicitudDevolucion r = solicitud(c, rechazada, false, false);
        solicitudes.rechazar(r.getId(), "fuera de plazo", "admin-1", Instant.now());

        assertThat(solicitudes.devueltasPorSector(EVENTO))
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.sectorId()).isEqualTo("platea");
                    assertThat(t.cantidad()).isEqualTo(1);
                    assertThat(t.importe()).isEqualByComparingTo("10000");
                });
        CantidadEImporte enCurso = solicitudes.enCurso(EVENTO);
        assertThat(enCurso.cantidad()).as("aprobada sin terminar y pendiente").isEqualTo(2);
        assertThat(enCurso.importe()).isEqualByComparingTo("15000");
    }

    @Test
    void lasEntradasDevueltasPorCancelacionSeCuentanPorSectorConSuPrecio() {
        Lugar p1 = lugar("platea", "10000.00");
        Lugar p2 = lugar("platea", "10000.00");
        Lugar c1 = lugar("campo", "5000.00");
        reintegroDeCancelacion(confirmada(p1, c1), EstadoReintegro.EXITOSO, p1, c1);
        // Un reintegro todavía pendiente: sus entradas no están devueltas
        reintegroDeCancelacion(confirmada(p2), EstadoReintegro.PENDIENTE, p2);

        assertThat(entradasDeReintegro.devueltasPorCancelacion(EVENTO))
                .extracting(TotalPorSector::sectorId, TotalPorSector::cantidad)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("campo", 1L),
                        org.assertj.core.groups.Tuple.tuple("platea", 1L));
        assertThat(entradasDeReintegro.devueltasPorCancelacion(EVENTO))
                .extracting(TotalPorSector::importe)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(pesos("5000"), pesos("10000"));
    }

    @Test
    void unaDevolucionYUnaCancelacionDeLaMismaCompraNoSeSolapan() {
        Lugar devuelta = lugar("platea", "10000.00");
        Lugar porCancelacion = lugar("platea", "10000.00");
        Compra c = confirmada(devuelta, porCancelacion);
        solicitud(c, devuelta, true, true);
        // La cancelación excluye la entrada con devolución aprobada (CancelacionService)
        reintegroDeCancelacion(c, EstadoReintegro.EXITOSO, porCancelacion);

        assertThat(solicitudes.devueltasPorSector(EVENTO).get(0).cantidad()).isEqualTo(1);
        assertThat(entradasDeReintegro.devueltasPorCancelacion(EVENTO).get(0).cantidad()).isEqualTo(1);
    }

    @Test
    void lasDevolucionesDeOtroEventoNoCuentan() {
        Lugar l = lugar("platea", "10000.00");
        Compra otra = compra(OTRO_EVENTO, EstadoCompra.CONFIRMADA, "pago-1", l);
        solicitud(otra, l, true, true);
        Lugar l2 = lugar("platea", "10000.00");
        reintegroDeCancelacion(compra(OTRO_EVENTO, EstadoCompra.CONFIRMADA, "pago-2", l2), EstadoReintegro.EXITOSO, l2);

        assertThat(solicitudes.devueltasPorSector(EVENTO)).isEmpty();
        assertThat(entradasDeReintegro.devueltasPorCancelacion(EVENTO)).isEmpty();
        assertThat(solicitudes.enCurso(EVENTO).cantidad()).isZero();
    }

    // ------------------------------------------------------------------ recaudación

    @Test
    void elReintegroDeUnaDevolucionSeAtribuyeAlSectorDeLaSolicitud() {
        Lugar platea = lugar("platea", "10000.00");
        Lugar campo = lugar("campo", "5000.00");
        Compra c = confirmada(platea, campo);
        reintegroDeDevolucion(solicitud(c, platea, true, true), EstadoReintegro.EXITOSO);
        reintegroDeDevolucion(solicitud(c, campo, true, false), EstadoReintegro.FALLIDO);

        assertThat(reintegros.reintegrosDeDevolucionPorSector(EVENTO))
                .extracting(ReintegroPorSector::sectorId, ReintegroPorSector::estado)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("platea", EstadoReintegro.EXITOSO),
                        org.assertj.core.groups.Tuple.tuple("campo", EstadoReintegro.FALLIDO));
        assertThat(reintegros.reintegrosDeDevolucionPorSector(EVENTO))
                .filteredOn(r -> r.sectorId().equals("platea")).singleElement()
                .satisfies(r -> assertThat(r.importe()).isEqualByComparingTo("10000"));
    }

    @Test
    void elReintegroPorCancelacionSeRepartePorSectorSegunElPrecioDeCadaEntrada() {
        Lugar platea = lugar("platea", "10000.00");
        Lugar campo = lugar("campo", "5000.00");
        reintegroDeCancelacion(confirmada(platea, campo), EstadoReintegro.EXITOSO, platea, campo);

        assertThat(reintegros.reintegrosDeCancelacionPorSector(EVENTO))
                .extracting(ReintegroPorSector::sectorId, r -> r.importe().stripTrailingZeros().toPlainString(),
                        ReintegroPorSector::estado)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("platea", "10000", EstadoReintegro.EXITOSO),
                        org.assertj.core.groups.Tuple.tuple("campo", "5000", EstadoReintegro.EXITOSO));
    }

    @Test
    void losReintegrosDeComprasConfirmadasSeAgrupanPorEstado() {
        Lugar a = lugar("platea", "10000.00");
        Lugar b = lugar("platea", "10000.00");
        Lugar d = lugar("campo", "5000.00");
        Compra c = confirmada(a, b, d);
        reintegroDeDevolucion(solicitud(c, a, true, true), EstadoReintegro.EXITOSO);
        reintegroDeDevolucion(solicitud(c, b, true, false), EstadoReintegro.PENDIENTE);
        reintegroDeDevolucion(solicitud(c, d, true, false), EstadoReintegro.FALLIDO);

        assertThat(reintegros.reintegrosDeConfirmadasPorEstado(EVENTO))
                .extracting(ReintegrosPorEstado::estado, ReintegrosPorEstado::cantidad,
                        r -> r.importe().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(EstadoReintegro.EXITOSO, 1L, "10000"),
                        org.assertj.core.groups.Tuple.tuple(EstadoReintegro.PENDIENTE, 1L, "10000"),
                        org.assertj.core.groups.Tuple.tuple(EstadoReintegro.FALLIDO, 1L, "5000"));
    }

    @Test
    void losCobrosYReintegrosDeComprasNoConfirmadasVanAparte() {
        // Venta rechazada después del cobro: compra FALLIDA con reintegro automático exitoso
        Compra rechazada = compra(EVENTO, EstadoCompra.FALLIDA, "pago-1", lugar("platea", "10000.00"));
        reintegro(rechazada, UUID.randomUUID(), "10000.00", OrigenReintegro.VENTA_RECHAZADA, EstadoReintegro.EXITOSO);
        // Evento cerrado al emitir: compra FALLIDA con reintegro por cancelación todavía pendiente
        Compra cerrada = compra(EVENTO, EstadoCompra.FALLIDA, "pago-2", lugar("campo", "5000.00"));
        reintegro(cerrada, UUID.randomUUID(), "5000.00", OrigenReintegro.CANCELACION_EVENTO, EstadoReintegro.PENDIENTE);

        assertThat(reintegros.reintegrosDeConfirmadasPorEstado(EVENTO)).isEmpty();
        assertThat(reintegros.reintegrosDeCancelacionPorSector(EVENTO)).isEmpty();
        assertThat(reintegros.reintegradoDeNoConfirmadas(EVENTO)).isEqualByComparingTo("10000");
        CantidadEImporte cobradas = compras.cobradasNoConfirmadas(EVENTO);
        assertThat(cobradas.cantidad()).isEqualTo(2);
        assertThat(cobradas.importe()).isEqualByComparingTo("15000");
    }

    @Test
    void sinReintegrosNoConfirmadosLoReintegradoEsNulo() {
        assertThat(reintegros.reintegradoDeNoConfirmadas(EVENTO)).isNull();
    }
}
