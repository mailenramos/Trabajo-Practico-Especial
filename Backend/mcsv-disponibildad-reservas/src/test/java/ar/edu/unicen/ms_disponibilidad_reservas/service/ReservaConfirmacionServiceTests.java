package ar.edu.unicen.ms_disponibilidad_reservas.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import ar.edu.unicen.ms_disponibilidad_reservas.dto.CrearReservaRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.DetalleReservaSolicitud;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.EstadoVenta;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.InicializarDisponibilidadRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.SectorInicializacionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.model.CantidadClienteEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.EventoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.CantidadClienteEventoRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.EventoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.ReservaRepository;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false"
})
@Import({ ReservaService.class, DisponibilidadService.class, DisponibilidadAgotamientoService.class,
        CantidadClienteEventoService.class,
        InicializacionDisponibilidadService.class, EstadoVentaService.class,
        ReservaConfirmacionServiceTests.ClockConfig.class,
        ReservaConfirmacionServiceTests.ValidationConfig.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
class ReservaConfirmacionServiceTests {

    private static final String EVENTO = "EVT-CONFIRMACION";
    private static final String CLIENTE = "CLIENTE-CONFIRMACION";
    private static final Instant AHORA = Instant.parse("2030-01-01T12:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock clock() {
            return new MutableClock(AHORA);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ValidationConfig {
        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant initial) {
            this.instant = new AtomicReference<>(initial);
        }

        void setInstant(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("La prueba utiliza UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    @Autowired
    private ReservaService reservaService;

    @Autowired
    private EstadoVentaService estadoVentaService;

    @Autowired
    private InicializacionDisponibilidadService inicializacionService;

    @Autowired
    private EventoDisponibilidadRepository eventoRepository;

    @Autowired
    private ReservaRepository reservaRepository;

    @Autowired
    private CantidadClienteEventoRepository cantidadesRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void limpiar() {
        clock.setInstant(AHORA);
        jdbc.update("DELETE FROM detalles_reserva");
        jdbc.update("DELETE FROM reservas");
        jdbc.update("DELETE FROM cantidades_cliente_evento");
        jdbc.update("DELETE FROM asientos_disponibilidad");
        jdbc.update("DELETE FROM sectores_disponibilidad");
        jdbc.update("DELETE FROM eventos_disponibilidad");
    }

    @Test
    void confirmsNumberedAndNonNumberedInventoryAndMovesClientCountsExactlyOnce() {
        inicializar(EVENTO, true);
        var created = reservaService.crear(EVENTO, CLIENTE,
                request("SOL-CONFIRMAR", asiento(), cantidad()));
        String reservaId = created.reserva().reservaId();

        reservaService.confirmar(reservaId);

        assertEquals("CONFIRMADA", jdbc.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, reservaId));
        assertEquals("VENDIDO", asientoEstado());
        assertEquals(0, sectorReservado());
        assertEquals(2, sectorVendido());
        assertEquals(0, cantidadCliente("cantidad_reservada"));
        assertEquals(3, cantidadCliente("cantidad_confirmada"));

        reservaService.confirmar(reservaId);

        assertEquals("VENDIDO", asientoEstado());
        assertEquals(0, sectorReservado());
        assertEquals(2, sectorVendido());
        assertEquals(0, cantidadCliente("cantidad_reservada"));
        assertEquals(3, cantidadCliente("cantidad_confirmada"));
    }

    @Test
    void acceptsConfirmationImmediatelyBeforeExpiryAndRejectsAtTheExactExpiryInstant() {
        inicializar(EVENTO, false);
        var valid = reservaService.crear(EVENTO, CLIENTE,
                request("SOL-ANTES-VENCIMIENTO", asiento()));
        Instant expiraEn = valid.reserva().expiraEn();
        clock.setInstant(expiraEn.minusNanos(1));

        reservaService.confirmar(valid.reserva().reservaId());
        assertEquals("CONFIRMADA", jdbc.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, valid.reserva().reservaId()));

        inicializar("EVT-EXACT-EXPIRY", false);
        var expired = reservaService.crear("EVT-EXACT-EXPIRY", CLIENTE,
                request("SOL-EN-VENCIMIENTO", asiento()));
        clock.setInstant(expired.reserva().expiraEn());

        assertThrows(ReservaVencidaException.class,
                () -> reservaService.confirmar(expired.reserva().reservaId()));
        assertEquals("VIGENTE", jdbc.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, expired.reserva().reservaId()));
        assertEquals("RESERVADO", jdbc.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id=?", String.class, "EVT-EXACT-EXPIRY"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT cantidad_reservada FROM cantidades_cliente_evento WHERE usuario_id=? AND evento_id='EVT-EXACT-EXPIRY'",
                Integer.class, CLIENTE));
    }

    @Test
    void rejectsReleasedAndMissingReservations() {
        inicializar(EVENTO, false);
        var created = reservaService.crear(EVENTO, CLIENTE,
                request("SOL-LIBERADA", asiento()));
        jdbc.update("UPDATE reservas SET estado='LIBERADA' WHERE id=?", created.reserva().reservaId());

        assertThrows(ReservaLiberadaException.class,
                () -> reservaService.confirmar(created.reserva().reservaId()));
        assertThrows(ReservaNoEncontradaException.class,
                () -> reservaService.confirmar("00000000-0000-0000-0000-000000000000"));
        assertEquals("RESERVADO", asientoEstado());
        assertEquals(1, cantidadCliente("cantidad_reservada"));
        assertEquals(0, cantidadCliente("cantidad_confirmada"));
    }

    @Test
    void inventoryFailureRollsBackEarlierSeatAndReservationCountUpdates() {
        inicializar(EVENTO, true);
        var created = reservaService.crear(EVENTO, CLIENTE,
                request("SOL-ROLLBACK-CONFIRMAR", asiento(), cantidad()));
        // Simulate inconsistent non-numbered reservation counts so that the numbered seat
        // update executes first and the later conditional sector update fails.
        jdbc.update("UPDATE sectores_disponibilidad SET cantidad_reservada=0 "
                + "WHERE evento_id=? AND sector_id='SEC-GEN'", EVENTO);

        assertThrows(DisponibilidadException.class,
                () -> reservaService.confirmar(created.reserva().reservaId()));

        assertEquals("VIGENTE", jdbc.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, created.reserva().reservaId()));
        assertEquals("RESERVADO", asientoEstado());
        assertEquals(0, sectorVendido());
        assertEquals(3, cantidadCliente("cantidad_reservada"));
        assertEquals(0, cantidadCliente("cantidad_confirmada"));
    }

    @Test
    void databaseFailureAtFinalReservationUpdateRollsBackInventoryAndBothClientCounts() {
        inicializar(EVENTO, true);
        var created = reservaService.crear(EVENTO, CLIENTE,
                request("SOL-ROLLBACK-FINAL", asiento(), cantidad()));
        jdbc.execute("ALTER TABLE reservas ADD CONSTRAINT ck_test_reject_confirmation "
                + "CHECK (estado <> 'CONFIRMADA')");
        try {
            assertThrows(DataIntegrityViolationException.class,
                    () -> reservaService.confirmar(created.reserva().reservaId()));

            assertEquals("VIGENTE", jdbc.queryForObject(
                    "SELECT estado FROM reservas WHERE id=?", String.class, created.reserva().reservaId()));
            assertEquals("RESERVADO", asientoEstado());
            assertEquals(2, sectorReservado());
            assertEquals(0, sectorVendido());
            assertEquals(3, cantidadCliente("cantidad_reservada"));
            assertEquals(0, cantidadCliente("cantidad_confirmada"));
        } finally {
            jdbc.execute("ALTER TABLE reservas DROP CONSTRAINT ck_test_reject_confirmation");
        }
    }

    @Test
    void concurrentConfirmationsOfOneReservationApplyTheTransferOnlyOnce() throws Exception {
        inicializar(EVENTO, false);
        var created = reservaService.crear(EVENTO, CLIENTE,
                request("SOL-CONCURRENTE", asiento()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> confirmarTrasBarrera(ready, start,
                    created.reserva().reservaId()));
            Future<Boolean> second = executor.submit(() -> confirmarTrasBarrera(ready, start,
                    created.reserva().reservaId()));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(first.get(20, TimeUnit.SECONDS));
            assertTrue(second.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals("CONFIRMADA", jdbc.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, created.reserva().reservaId()));
        assertEquals("VENDIDO", asientoEstado());
        assertEquals(0, cantidadCliente("cantidad_reservada"));
        assertEquals(1, cantidadCliente("cantidad_confirmada"));
    }

    @Test
    void paymentRejectionReleasesNumberedAndNonNumberedInventoryAndCountsExactlyOnce() {
        inicializar(EVENTO, true);
        var created = reservaService.crear(EVENTO, CLIENTE,
                request("SOL-PAGO-RECHAZADO", asiento(), cantidad()));
        String reservaId = created.reserva().reservaId();

        reservaService.liberarPorPagoRechazado(reservaId);

        assertEquals("LIBERADA", jdbc.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, reservaId));
        assertEquals("LIBRE", asientoEstado());
        assertEquals(0, sectorReservado());
        assertEquals(0, sectorVendido());
        assertEquals(0, cantidadCliente("cantidad_reservada"));
        assertEquals(0, cantidadCliente("cantidad_confirmada"));

        reservaService.liberarPorPagoRechazado(reservaId);
        assertEquals("LIBRE", asientoEstado());
        assertEquals(0, sectorReservado());
        assertEquals(0, cantidadCliente("cantidad_reservada"));
        assertEquals(0, cantidadCliente("cantidad_confirmada"));
    }

    @Test
    void paymentRejectionCanReleaseExpiredReservationStillStoredAsVigente() {
        inicializar(EVENTO, false);
        var created = reservaService.crear(EVENTO, CLIENTE,
                request("SOL-PAGO-RECHAZADO-VENCIDA", asiento()));
        clock.setInstant(created.reserva().expiraEn().plusNanos(1));

        reservaService.liberarPorPagoRechazado(created.reserva().reservaId());

        assertEquals("LIBERADA", jdbc.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, created.reserva().reservaId()));
        assertEquals("LIBRE", asientoEstado());
        assertEquals(0, cantidadCliente("cantidad_reservada"));
    }

    @Test
    void paymentRejectionAndExpirationRestoreClientEventLimitExactlyOnce() {
        inicializarConLimite("EVT-LIMIT-RELEASE", 4);
        var rejected = reservaService.crear("EVT-LIMIT-RELEASE", CLIENTE,
                request("SOL-LIMIT-RELEASE", cantidad("SEC-GEN", 1)));
        reservaService.liberarPorPagoRechazado(rejected.reserva().reservaId());
        assertEquals(0, cantidadCliente(CLIENTE, "EVT-LIMIT-RELEASE", "cantidad_reservada"));
        var afterRelease = reservaService.crear("EVT-LIMIT-RELEASE", CLIENTE,
                request("SOL-LIMIT-AFTER-RELEASE", cantidad("SEC-GEN", 1)));
        assertEquals(201, afterRelease.status().value());
        jdbc.update("UPDATE reservas SET expira_en = TIMESTAMPADD(HOUR, 1, expira_en) WHERE id = ?",
                afterRelease.reserva().reservaId());

        inicializarConLimite("EVT-LIMIT-EXPIRATION", 4);
        var expiring = reservaService.crear("EVT-LIMIT-EXPIRATION", CLIENTE,
                request("SOL-LIMIT-EXPIRING", cantidad("SEC-GEN", 1)));
        clock.setInstant(expiring.reserva().expiraEn());
        assertEquals(1, reservaService.expirarReservasVencidas());
        assertEquals(0, cantidadCliente(CLIENTE, "EVT-LIMIT-EXPIRATION", "cantidad_reservada"));
        assertEquals(201, reservaService.crear("EVT-LIMIT-EXPIRATION", CLIENTE,
                request("SOL-LIMIT-AFTER-EXPIRATION", cantidad("SEC-GEN", 1))).status().value());
    }

    @Test
    void cancellationAndFinalizationReleaseHoldsButPreserveConfirmedClientQuantities() {
        for (EstadoVenta terminal : List.of(EstadoVenta.CANCELADA, EstadoVenta.FINALIZADA)) {
            String eventoId = "EVT-TERMINAL-LIMIT-" + terminal;
            inicializarConLimite(eventoId, 8);
            var confirmed = reservaService.crear(eventoId, CLIENTE,
                    request("SOL-TERMINAL-CONFIRMED-" + terminal, cantidad("SEC-GEN", 2)));
            reservaService.confirmar(confirmed.reserva().reservaId());
            var activeHold = reservaService.crear(eventoId, CLIENTE,
                    request("SOL-TERMINAL-HOLD-" + terminal, cantidad("SEC-GEN", 2)));

            estadoVentaService.aplicar(eventoId, terminal);

            assertEquals("CONFIRMADA", jdbc.queryForObject("SELECT estado FROM reservas WHERE id=?",
                    String.class, confirmed.reserva().reservaId()));
            assertEquals("LIBERADA", jdbc.queryForObject("SELECT estado FROM reservas WHERE id=?",
                    String.class, activeHold.reserva().reservaId()));
            assertEquals(0, cantidadCliente(CLIENTE, eventoId, "cantidad_reservada"));
            assertEquals(2, cantidadCliente(CLIENTE, eventoId, "cantidad_confirmada"));
            assertEquals(0, jdbc.queryForObject("SELECT cantidad_reservada FROM sectores_disponibilidad "
                    + "WHERE evento_id=? AND sector_id='SEC-GEN'", Integer.class, eventoId));
            assertEquals(2, jdbc.queryForObject("SELECT cantidad_vendida FROM sectores_disponibilidad "
                    + "WHERE evento_id=? AND sector_id='SEC-GEN'", Integer.class, eventoId));
        }
    }

    @Test
    void expirationDoesNotReleaseReservationUntilItsExactExpiryInstant() {
        inicializar(EVENTO, true);
        var created = reservaService.crear(EVENTO, CLIENTE,
                request("SOL-EXPIRACION-FUTURA", asiento(), cantidad()));
        Instant expiraEn = created.reserva().expiraEn();

        clock.setInstant(expiraEn.minusNanos(1));
        assertEquals(0, reservaService.expirarReservasVencidas());
        assertEquals("VIGENTE", estadoReserva(created.reserva().reservaId()));
        assertEquals("RESERVADO", asientoEstado());
        assertEquals(2, sectorReservado());
        assertEquals(3, cantidadCliente("cantidad_reservada"));

        clock.setInstant(expiraEn);
        assertEquals(1, reservaService.expirarReservasVencidas());
        assertEquals("LIBERADA", estadoReserva(created.reserva().reservaId()));
        assertEquals("LIBRE", asientoEstado());
        assertEquals(0, sectorReservado());
        assertEquals(0, cantidadCliente("cantidad_reservada"));
        assertEquals(0, cantidadCliente("cantidad_confirmada"));
    }

    @Test
    void expirationSkipsConfirmedAndAlreadyReleasedReservations() {
        inicializar(EVENTO, false);
        var confirmed = reservaService.crear(EVENTO, CLIENTE,
                request("SOL-EXPIRACION-CONFIRMADA", asiento()));
        reservaService.confirmar(confirmed.reserva().reservaId());

        inicializar("EVT-EXPIRACION-LIBERADA", false);
        var released = reservaService.crear("EVT-EXPIRACION-LIBERADA", CLIENTE,
                request("SOL-EXPIRACION-LIBERADA", asiento()));
        reservaService.liberarPorPagoRechazado(released.reserva().reservaId());

        clock.setInstant(confirmed.reserva().expiraEn());
        assertEquals(0, reservaService.expirarReservasVencidas());
        assertEquals("CONFIRMADA", estadoReserva(confirmed.reserva().reservaId()));
        assertEquals("VENDIDO", jdbc.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id=?", String.class, EVENTO));
        assertEquals("LIBERADA", estadoReserva(released.reserva().reservaId()));
        assertEquals("LIBRE", jdbc.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id=?", String.class,
                "EVT-EXPIRACION-LIBERADA"));
    }

    @Test
    void expirationProcessesSeveralDueReservationsInOneInvocation() {
        inicializar(EVENTO, true);
        var detalle = new DetalleReservaSolicitud("SEC-GEN", null, null, 1);
        var primera = reservaService.crear(EVENTO, "CLIENTE-LOTE-1",
                request("SOL-EXPIRACION-LOTE-1", detalle));
        var segunda = reservaService.crear(EVENTO, "CLIENTE-LOTE-2",
                request("SOL-EXPIRACION-LOTE-2", detalle));
        var tercera = reservaService.crear(EVENTO, "CLIENTE-LOTE-3",
                request("SOL-EXPIRACION-LOTE-3", detalle));
        clock.setInstant(primera.reserva().expiraEn());

        assertEquals(3, reservaService.expirarReservasVencidas());
        assertEquals("LIBERADA", estadoReserva(primera.reserva().reservaId()));
        assertEquals("LIBERADA", estadoReserva(segunda.reserva().reservaId()));
        assertEquals("LIBERADA", estadoReserva(tercera.reserva().reservaId()));
        assertEquals(0, sectorReservado());
        assertEquals(0, sectorVendido());
        assertEquals(0, cantidadCliente("CLIENTE-LOTE-1", EVENTO, "cantidad_reservada"));
        assertEquals(0, cantidadCliente("CLIENTE-LOTE-2", EVENTO, "cantidad_reservada"));
        assertEquals(0, cantidadCliente("CLIENTE-LOTE-3", EVENTO, "cantidad_reservada"));
    }

    @Test
    void expirationProcessesEachReservationIndependentlyAndContinuesAfterRollback() {
        inicializar(EVENTO, true);
        var rechazable = reservaService.crear(EVENTO, "CLIENTE-ROLLBACK-EXPIRACION",
                request("SOL-EXPIRACION-ROLLBACK", cantidad()));
        var procesable = reservaService.crear(EVENTO, "CLIENTE-OK-EXPIRACION",
                request("SOL-EXPIRACION-OK", asiento()));

        jdbc.execute("ALTER TABLE reservas ADD CONSTRAINT ck_test_reject_one_expiry "
                + "CHECK (estado <> 'LIBERADA' OR id <> '" + rechazable.reserva().reservaId() + "')");
        try {
            clock.setInstant(rechazable.reserva().expiraEn());
            assertEquals(1, reservaService.expirarReservasVencidas());

            assertEquals("VIGENTE", estadoReserva(rechazable.reserva().reservaId()));
            assertEquals("LIBERADA", estadoReserva(procesable.reserva().reservaId()));
            assertEquals(2, sectorReservado());
            assertEquals("LIBRE", jdbc.queryForObject(
                    "SELECT estado FROM asientos_disponibilidad WHERE evento_id=?", String.class, EVENTO));
            assertEquals(2, cantidadCliente("CLIENTE-ROLLBACK-EXPIRACION", EVENTO,
                    "cantidad_reservada"));
            assertEquals(0, cantidadCliente("CLIENTE-OK-EXPIRACION", EVENTO,
                    "cantidad_reservada"));
        } finally {
            jdbc.execute("ALTER TABLE reservas DROP CONSTRAINT ck_test_reject_one_expiry");
        }
    }

    @Test
    void expirationAndConfirmationAndManualReleaseSerializeWithoutDoubleChanges() throws Exception {
        inicializar(EVENTO, true);
        var created = reservaService.crear(EVENTO, CLIENTE,
                request("SOL-EXPIRACION-CONCURRENTE", asiento(), cantidad()));
        clock.setInstant(created.reserva().expiraEn());

        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<Integer> expiration = executor.submit(() -> {
                awaitBarrier(ready, start);
                return reservaService.expirarReservasVencidas();
            });
            Future<Boolean> confirmation = executor.submit(() -> {
                awaitBarrier(ready, start);
                try {
                    reservaService.confirmar(created.reserva().reservaId());
                } catch (ReservaVencidaException | ReservaLiberadaException expected) {
                    return true;
                }
                return true;
            });
            Future<Boolean> manualRelease = executor.submit(() -> {
                awaitBarrier(ready, start);
                reservaService.liberarPorPagoRechazado(created.reserva().reservaId());
                return true;
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(expiration.get(20, TimeUnit.SECONDS) >= 0);
            assertTrue(confirmation.get(20, TimeUnit.SECONDS));
            assertTrue(manualRelease.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals("LIBERADA", estadoReserva(created.reserva().reservaId()));
        assertEquals("LIBRE", asientoEstado());
        assertEquals(0, sectorReservado());
        assertEquals(0, cantidadCliente("cantidad_reservada"));
        assertEquals(0, cantidadCliente("cantidad_confirmada"));
    }

    private void awaitBarrier(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
    }

    private String estadoReserva(String reservaId) {
        return jdbc.queryForObject("SELECT estado FROM reservas WHERE id=?", String.class, reservaId);
    }

    @Test
    void paymentRejectionRejectsConfirmedAndMissingReservationsWithoutChangingSoldInventory() {
        inicializar(EVENTO, false);
        var created = reservaService.crear(EVENTO, CLIENTE,
                request("SOL-PAGO-RECHAZADO-CONFIRMADA", asiento()));
        reservaService.confirmar(created.reserva().reservaId());

        assertThrows(ReservaConflictException.class,
                () -> reservaService.liberarPorPagoRechazado(created.reserva().reservaId()));
        assertThrows(ReservaNoEncontradaException.class,
                () -> reservaService.liberarPorPagoRechazado("00000000-0000-0000-0000-000000000000"));
        assertEquals("CONFIRMADA", jdbc.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, created.reserva().reservaId()));
        assertEquals("VENDIDO", asientoEstado());
        assertEquals(0, cantidadCliente("cantidad_reservada"));
        assertEquals(1, cantidadCliente("cantidad_confirmada"));
    }

    @Test
    void databaseFailureAtFinalReleaseUpdateRollsBackInventoryAndClientCounts() {
        inicializar(EVENTO, true);
        var created = reservaService.crear(EVENTO, CLIENTE,
                request("SOL-LIBERACION-ROLLBACK", asiento(), cantidad()));
        jdbc.execute("ALTER TABLE reservas ADD CONSTRAINT ck_test_reject_release CHECK (estado <> 'LIBERADA')");
        try {
            assertThrows(DataIntegrityViolationException.class,
                    () -> reservaService.liberarPorPagoRechazado(created.reserva().reservaId()));

            assertEquals("VIGENTE", jdbc.queryForObject(
                    "SELECT estado FROM reservas WHERE id=?", String.class, created.reserva().reservaId()));
            assertEquals("RESERVADO", asientoEstado());
            assertEquals(2, sectorReservado());
            assertEquals(0, sectorVendido());
            assertEquals(3, cantidadCliente("cantidad_reservada"));
            assertEquals(0, cantidadCliente("cantidad_confirmada"));
        } finally {
            jdbc.execute("ALTER TABLE reservas DROP CONSTRAINT ck_test_reject_release");
        }
    }

    @Test
    void concurrentPaymentRejectionReleasesTheSameReservationOnlyOnce() throws Exception {
        inicializar(EVENTO, true);
        var created = reservaService.crear(EVENTO, CLIENTE,
                request("SOL-LIBERACION-CONCURRENTE", asiento(), cantidad()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> liberarTrasBarrera(ready, start,
                    created.reserva().reservaId()));
            Future<Boolean> second = executor.submit(() -> liberarTrasBarrera(ready, start,
                    created.reserva().reservaId()));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(first.get(20, TimeUnit.SECONDS));
            assertTrue(second.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals("LIBERADA", jdbc.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, created.reserva().reservaId()));
        assertEquals("LIBRE", asientoEstado());
        assertEquals(0, sectorReservado());
        assertEquals(0, cantidadCliente("cantidad_reservada"));
        assertEquals(0, cantidadCliente("cantidad_confirmada"));
    }

    private boolean liberarTrasBarrera(CountDownLatch ready, CountDownLatch start, String reservaId)
            throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        reservaService.liberarPorPagoRechazado(reservaId);
        return true;
    }

    private boolean confirmarTrasBarrera(CountDownLatch ready, CountDownLatch start, String reservaId)
            throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        reservaService.confirmar(reservaId);
        return true;
    }

    private void inicializar(String eventoId, boolean noNumerado) {
        var sectores = new java.util.ArrayList<SectorInicializacionRequest>();
        sectores.add(new SectorInicializacionRequest("SEC-NUM", TipoSector.NUMERADO, 1,
                List.of(new ar.edu.unicen.ms_disponibilidad_reservas.dto.AsientoInicializacionRequest(1, 1))));
        if (noNumerado) {
            sectores.add(new SectorInicializacionRequest("SEC-GEN", TipoSector.NO_NUMERADO, 4, List.of()));
        }
        inicializacionService.inicializar(eventoId, new InicializarDisponibilidadRequest(4, sectores));
        EventoDisponibilidad evento = eventoRepository.findByEventoId(eventoId).orElseThrow();
        evento.setEstadoEvento(EstadoEvento.PUBLICADO);
        eventoRepository.saveAndFlush(evento);
    }

    private void inicializarConLimite(String eventoId, int capacidad) {
        inicializacionService.inicializar(eventoId, new InicializarDisponibilidadRequest(4,
                List.of(new SectorInicializacionRequest("SEC-GEN", TipoSector.NO_NUMERADO, capacidad, List.of()))));
        EventoDisponibilidad evento = eventoRepository.findByEventoId(eventoId).orElseThrow();
        evento.setEstadoEvento(EstadoEvento.PUBLICADO);
        eventoRepository.saveAndFlush(evento);
    }

    private CrearReservaRequest request(String solicitudId, DetalleReservaSolicitud... detalles) {
        return new CrearReservaRequest(solicitudId, List.of(detalles));
    }

    private DetalleReservaSolicitud asiento() {
        return new DetalleReservaSolicitud("SEC-NUM", 1, 1, null);
    }

    private DetalleReservaSolicitud cantidad() {
        return new DetalleReservaSolicitud("SEC-GEN", null, null, 2);
    }

    private DetalleReservaSolicitud cantidad(String sectorId, int cantidad) {
        return new DetalleReservaSolicitud(sectorId, null, null, cantidad);
    }

    private String asientoEstado() {
        return jdbc.queryForObject("SELECT estado FROM asientos_disponibilidad WHERE evento_id=?", String.class,
                EVENTO);
    }

    private int sectorReservado() {
        return jdbc.queryForObject("SELECT cantidad_reservada FROM sectores_disponibilidad "
                + "WHERE evento_id=? AND sector_id='SEC-GEN'", Integer.class, EVENTO);
    }

    private int sectorVendido() {
        return jdbc.queryForObject("SELECT cantidad_vendida FROM sectores_disponibilidad "
                + "WHERE evento_id=? AND sector_id='SEC-GEN'", Integer.class, EVENTO);
    }

    private int cantidadCliente(String column) {
        return cantidadCliente(CLIENTE, EVENTO, column);
    }

    private int cantidadCliente(String usuarioId, String eventoId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM cantidades_cliente_evento "
                + "WHERE usuario_id=? AND evento_id=?", Integer.class, usuarioId, eventoId);
    }
}
