package ar.edu.unicen.ms_disponibilidad_reservas.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.*;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.*;

/** Shared regression scenarios; the MySQL subclass runs them on a fresh disposable server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:r2-lock-order;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false", "ms2.reservas.expiracion.fixed-delay-ms=3600000",
        "ms2.outbox.fixed-delay-ms=3600000"
})
@DirtiesContext
class R2LockOrderTests {
    @Autowired ReservaService reservas;
    @Autowired EstadoVentaService ventas;
    @Autowired DevolucionService devoluciones;
    @Autowired InicializacionDisponibilidadService inicializacion;
    @Autowired JdbcTemplate db;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @MockitoSpyBean ReservaRepository reservaRepository;
    @MockitoSpyBean EventoDisponibilidadRepository eventoRepository;
    @MockitoSpyBean CantidadClienteEventoRepository contadorRepository;

    String init() {
        String event = "R2-" + UUID.randomUUID();
        inicializacion.inicializar(event, new InicializarDisponibilidadRequest(10, List.of(
                new SectorInicializacionRequest("NUM", TipoSector.NUMERADO, 4, List.of(
                        new AsientoInicializacionRequest(1, 1), new AsientoInicializacionRequest(1, 2),
                        new AsientoInicializacionRequest(1, 3), new AsientoInicializacionRequest(1, 4))),
                new SectorInicializacionRequest("GEN", TipoSector.NO_NUMERADO, 20, List.of()))));
        ventas.aplicar(event, EstadoVenta.ACTIVA);
        return event;
    }
    String hold(String event, int seat) {
        return reservas.crear(event, "CLIENT-" + event, new CrearReservaRequest(UUID.randomUUID().toString(),
                List.of(new DetalleReservaSolicitud("NUM", 1, seat, null),
                        new DetalleReservaSolicitud("GEN", null, null, 1)))).reserva().reservaId();
    }
    void due(String id) {
        db.update("UPDATE reservas SET expira_en=? WHERE id=?",
                java.time.LocalDateTime.of(2000, 1, 1, 0, 0), id);
    }
    String state(String id) { return db.queryForObject("SELECT estado FROM reservas WHERE id=?", String.class, id); }
    int counter(String event, String column) {
        return db.queryForObject("SELECT " + column + " FROM cantidades_cliente_evento WHERE evento_id=?", Integer.class, event);
    }
    void released(String event, String... ids) {
        for (String id : ids) assertEquals("LIBERADA", state(id));
        assertEquals(0, counter(event, "cantidad_reservada"));
        assertEquals(0, counter(event, "cantidad_confirmada"));
        assertEquals(0, db.queryForObject("SELECT SUM(cantidad_reservada+cantidad_vendida) FROM sectores_disponibilidad WHERE evento_id=?", Integer.class, event));
        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM asientos_disponibilidad WHERE evento_id=? AND estado='LIBRE'", Integer.class, event));
    }
    static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Controlled lock checkpoint was not reached");
    }
    static Callable<Void> named(String name, Runnable action) {
        return () -> { String old = Thread.currentThread().getName(); Thread.currentThread().setName(name);
            try { action.run(); return null; } finally { Thread.currentThread().setName(old); } };
    }

    @ParameterizedTest
    @CsvSource({"CANCELADA,true", "FINALIZADA,true", "CANCELADA,false", "FINALIZADA,false"})
    void bulkReleaseAndIndividualReleaseUseEventBeforeReservation(EstadoVenta terminal, boolean expire) throws Exception {
        String event = init();
        var ids = new ArrayList<>(List.of(hold(event, 1), hold(event, 2)));
        Collections.sort(ids);
        String first = ids.get(0), second = ids.get(1);
        if (expire) due(second);
        CountDownLatch bulkHasFirstReservation = new CountDownLatch(1);
        CountDownLatch individualRequestsEvent = new CountDownLatch(1);
        CountDownLatch continueBulk = new CountDownLatch(1);
        AtomicBoolean individualTookReservation = new AtomicBoolean();
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("R2-individual")) individualTookReservation.set(true);
            return entityManager.createQuery("SELECT r FROM Reserva r WHERE r.id=:id", ar.edu.unicen.ms_disponibilidad_reservas.model.Reserva.class)
                    .setParameter("id", invocation.getArgument(0)).setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                    .getResultStream().findFirst();
        }).when(reservaRepository).findByIdForUpdate(anyString());
        AtomicBoolean firstCounter = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object result = entityManager.createQuery("SELECT c FROM CantidadClienteEvento c WHERE c.usuarioId=:user AND c.eventoId=:event",
                    ar.edu.unicen.ms_disponibilidad_reservas.model.CantidadClienteEvento.class)
                    .setParameter("user", invocation.getArgument(0)).setParameter("event", event)
                    .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst();
            if (Thread.currentThread().getName().equals("R2-bulk") && firstCounter.getAndSet(false)) {
                // Bulk now owns R-A AND the shared counter C, but has not reached R-B.
                bulkHasFirstReservation.countDown(); await(continueBulk);
            }
            return result;
        }).when(contadorRepository).findByUsuarioIdAndEventoIdForUpdate(anyString(), eq(event));
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("R2-individual")) individualRequestsEvent.countDown();
            return entityManager.createQuery("SELECT e FROM EventoDisponibilidad e WHERE e.eventoId=:event",
                    ar.edu.unicen.ms_disponibilidad_reservas.model.EventoDisponibilidad.class)
                    .setParameter("event", event).setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                    .getResultStream().findFirst();
        }).when(eventoRepository).findByEventoIdForUpdate(event);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Void> bulk = pool.submit(named("R2-bulk", () -> ventas.aplicar(event, terminal)));
            try {
                if (!bulkHasFirstReservation.await(10, TimeUnit.SECONDS)) {
                    bulk.get(1, TimeUnit.SECONDS);
                    fail("Bulk did not reach the shared counter checkpoint");
                }
                Future<Void> individual = pool.submit(named("R2-individual", () -> {
                    if (expire) reservas.expirarReservasVencidas(); else reservas.liberarPorPagoRechazado(second);
                }));
                try {
                    await(individualRequestsEvent);
                    // Old code would hold R-B here, then wait for the shared counter.
                    assertFalse(individualTookReservation.get());
                } finally { continueBulk.countDown(); }
                bulk.get(15, TimeUnit.SECONDS); individual.get(15, TimeUnit.SECONDS);
            } finally { continueBulk.countDown(); }
        }
        released(event, first, second);
        assertEquals(terminal == EstadoVenta.CANCELADA ? "CANCELADO" : "FINALIZADO",
                db.queryForObject("SELECT estado_evento FROM eventos_disponibilidad WHERE evento_id=?", String.class, event));
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void expirationAndConfirmationCannotBothApplyTheirEffects(boolean expired) throws Exception {
        String event = init(), id = hold(event, 1);
        if (expired) due(id);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var expiry = pool.submit(() -> { await(start); return reservas.expirarReservasVencidas(); });
            var confirmation = pool.submit(() -> { await(start);
                if (expired) {
                    RuntimeException error = assertThrows(RuntimeException.class, () -> reservas.confirmar(id));
                    assertTrue(error instanceof ReservaVencidaException || error instanceof ReservaLiberadaException);
                } else reservas.confirmar(id);
                return null; });
            start.countDown(); expiry.get(15, TimeUnit.SECONDS); confirmation.get(15, TimeUnit.SECONDS);
        }
        if (expired) released(event, id);
        else {
            assertEquals("CONFIRMADA", state(id));
            assertEquals(0, counter(event, "cantidad_reservada"));
            assertEquals(2, counter(event, "cantidad_confirmada"));
            assertEquals(1, db.queryForObject("SELECT cantidad_vendida FROM sectores_disponibilidad WHERE evento_id=? AND sector_id='GEN'", Integer.class, event));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM asientos_disponibilidad WHERE evento_id=? AND estado='VENDIDO'", Integer.class, event));
        }
    }

    LiberarReservaRequest refund(String key) {
        return new LiberarReservaRequest("DEVOLUCION_APROBADA", key,
                List.of(new DetalleDevolucionRequest("NUM", 1, 1, null), new DetalleDevolucionRequest("GEN", null, null, 1)));
    }
    @ParameterizedTest
    @CsvSource({"true", "false"})
    void concurrentRefundsCannotDuplicateInventoryCountersOrLedger(boolean sameKey) throws Exception {
        String event = init(), id = hold(event, 1); reservas.confirmar(id);
        String key = UUID.randomUUID().toString(), other = sameKey ? key : UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { await(start); devoluciones.devolverAprobada(id, refund(key)); return null; });
            var b = pool.submit(() -> { await(start);
                if (sameKey) devoluciones.devolverAprobada(id, refund(other));
                else { try { devoluciones.devolverAprobada(id, refund(other)); } catch (ReservaConflictException expected) { } }
                return null; });
            start.countDown();
            if (sameKey) a.get(15, TimeUnit.SECONDS);
            else { try { a.get(15, TimeUnit.SECONDS); } catch (ExecutionException failure) { assertInstanceOf(ReservaConflictException.class, failure.getCause()); } }
            b.get(15, TimeUnit.SECONDS);
        }
        assertEquals("CONFIRMADA", state(id));
        assertEquals(0, counter(event, "cantidad_reservada")); assertEquals(0, counter(event, "cantidad_confirmada"));
        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM asientos_disponibilidad WHERE evento_id=? AND estado='LIBRE'", Integer.class, event));
        assertEquals(0, db.queryForObject("SELECT cantidad_vendida FROM sectores_disponibilidad WHERE evento_id=? AND sector_id='GEN'", Integer.class, event));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM devoluciones WHERE reserva_id=?", Integer.class, id));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM devolucion_detalles_asientos WHERE reserva_id=?", Integer.class, id));
        assertEquals(1, db.queryForObject("SELECT SUM(cantidad) FROM devolucion_detalles_cantidades WHERE reserva_id=?", Integer.class, id));
    }
    @Test
    void globalRefundKeyAcrossEventsHasOneWinnerWithoutCrossEventLockCycle() throws Exception {
        String firstEvent = init(), secondEvent = init();
        String first = hold(firstEvent, 1), second = hold(secondEvent, 1);
        reservas.confirmar(first); reservas.confirmar(second);
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> a = () -> { await(start);
            try { devoluciones.devolverAprobada(first, refund(key)); return true; }
            catch (ReservaConflictException expected) { return false; } };
        Callable<Boolean> b = () -> { await(start);
            try { devoluciones.devolverAprobada(second, refund(key)); return true; }
            catch (ReservaConflictException expected) { return false; } };
        boolean firstWon;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var fa = pool.submit(a); var fb = pool.submit(b); start.countDown();
            firstWon = fa.get(15, TimeUnit.SECONDS);
            assertNotEquals(firstWon, fb.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM devoluciones WHERE id=?", Integer.class, key));
        assertEquals(firstWon ? first : second, db.queryForObject("SELECT reserva_id FROM devoluciones WHERE id=?", String.class, key));
        for (String event : List.of(firstEvent, secondEvent)) {
            boolean won = event.equals(firstEvent) == firstWon;
            assertEquals(won ? 0 : 2, counter(event, "cantidad_confirmada"));
            assertEquals(won ? 0 : 1, db.queryForObject("SELECT cantidad_vendida FROM sectores_disponibilidad WHERE evento_id=? AND sector_id='GEN'", Integer.class, event));
            assertEquals(won ? 4 : 3, db.queryForObject("SELECT COUNT(*) FROM asientos_disponibilidad WHERE evento_id=? AND estado='LIBRE'", Integer.class, event));
        }
    }

    @Test
    void bulkRollbackAlsoUndoesPreviouslyProcessedReservation() {
        String event = init(); hold(event, 1); hold(event, 2);
        var before = snapshot();
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (writes.incrementAndGet() == 2) throw new IllegalStateException("R2 second reservation failure");
            Object merged = entityManager.merge(invocation.getArgument(0));
            entityManager.flush();
            return merged;
        }).when(contadorRepository).saveAndFlush(any());
        assertThrows(IllegalStateException.class, () -> ventas.aplicar(event, EstadoVenta.CANCELADA));
        assertEquals(2, writes.get());
        assertEquals(before, snapshot());
    }

    List<?> snapshot() {
        return List.of("eventos_disponibilidad", "reservas", "detalles_reserva", "cantidades_cliente_evento",
                "asientos_disponibilidad", "sectores_disponibilidad", "devoluciones", "devolucion_detalles_asientos", "devolucion_detalles_cantidades")
                .stream().map(t -> db.queryForList("SELECT * FROM " + t + " ORDER BY id")).toList();
    }
    @ParameterizedTest
    @CsvSource({"false", "true"})
    void failureAfterInventoryWritesRollsBackWholeReleaseOrRefund(boolean refund) {
        String event = init(), id = hold(event, 1);
        if (refund) reservas.confirmar(id);
        var before = snapshot();
        doThrow(new IllegalStateException("R2 injected failure after inventory")).when(contadorRepository).saveAndFlush(any());
        if (refund) assertThrows(IllegalStateException.class, () -> devoluciones.devolverAprobada(id, refund(UUID.randomUUID().toString())));
        else assertThrows(IllegalStateException.class, () -> reservas.liberarPorPagoRechazado(id));
        assertEquals(before, snapshot());
    }
}
