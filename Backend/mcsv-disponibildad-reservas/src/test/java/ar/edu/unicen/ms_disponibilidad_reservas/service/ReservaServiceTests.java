package ar.edu.unicen.ms_disponibilidad_reservas.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import ar.edu.unicen.ms_disponibilidad_reservas.config.ClockConfiguration;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.CrearReservaRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.DetalleReservaSolicitud;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.InicializarDisponibilidadRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.ReservaCreadaResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.SectorInicializacionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.model.EventoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.CantidadClienteEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.Reserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoReserva;
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
        InicializacionDisponibilidadService.class, ClockConfiguration.class, ReservaServiceTests.ValidationConfig.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
class ReservaServiceTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class ValidationConfig {
        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }
    }

    @Autowired
    private ReservaService reservaService;

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

    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM detalles_reserva");
        jdbc.update("DELETE FROM reservas");
        jdbc.update("DELETE FROM cantidades_cliente_evento");
        jdbc.update("DELETE FROM asientos_disponibilidad");
        jdbc.update("DELETE FROM sectores_disponibilidad");
        jdbc.update("DELETE FROM eventos_disponibilidad");
    }

    @Test
    void reservesNumberedSeatsAndPersistsTheOwnerAndTenMinuteUtcExpiry() {
        inicializar("EVT-NUM", 4, true, false);

        var result = reservaService.crear("EVT-NUM", "CLIENTE-1",
                request("SOL-1", seat("SEC-NUM", 1, 5), seat("SEC-NUM", 2, 6)));

        assertEquals(201, result.status().value());
        assertEquals(EstadoReserva.VIGENTE, result.reserva().estado());
        assertTrue(result.reserva().expiraEn().isAfter(java.time.Instant.now().plusSeconds(590)));
        assertTrue(result.reserva().expiraEn().isBefore(java.time.Instant.now().plusSeconds(610)));
        Reserva persisted = reservaRepository.findById(result.reserva().reservaId()).orElseThrow();
        assertEquals("CLIENTE-1", persisted.getUsuarioId());
        assertEquals("EVT-NUM", persisted.getEventoId());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM detalles_reserva WHERE reserva_id = ?",
                Integer.class, persisted.getId()));
        assertEquals(EstadoReserva.VIGENTE, persisted.getEstado());
        assertEquals(2, cantidadesRepository.findByUsuarioIdAndEventoId("CLIENTE-1", "EVT-NUM")
                .orElseThrow().getCantidadReservada());
        assertEquals("RESERVADO", seatState("EVT-NUM", "SEC-NUM", 1, 5));
        assertEquals("RESERVADO", seatState("EVT-NUM", "SEC-NUM", 2, 6));
        assertEquals(result.reserva().expiraEn(), persisted.getExpiraEn().toInstant(java.time.ZoneOffset.UTC));
    }

    @Test
    void reservesNonNumberedAndMixedInventoryAndCountsEveryPlace() {
        inicializar("EVT-MIX", 4, true, true);

        var result = reservaService.crear("EVT-MIX", "CLIENTE-1",
                request("SOL-MIX", seat("SEC-NUM", 1, 5), quantity("SEC-GEN", 2)));

        assertEquals(201, result.status().value());
        assertEquals(3, cantidadesRepository.findByUsuarioIdAndEventoId("CLIENTE-1", "EVT-MIX")
                .orElseThrow().getCantidadReservada());
        assertEquals(2, jdbc.queryForObject("SELECT cantidad_reservada FROM sectores_disponibilidad "
                + "WHERE evento_id='EVT-MIX' AND sector_id='SEC-GEN'", Integer.class));
    }

    @Test
    void identicalRetryReturnsTheExistingReservationWithoutChangingInventoryOrCounts() {
        inicializar("EVT-RETRY", 4, true, false);
        CrearReservaRequest request = request("SOL-RETRY", seat("SEC-NUM", 1, 5));
        var first = reservaService.crear("EVT-RETRY", "CLIENTE-1", request);
        var retry = reservaService.crear("EVT-RETRY", "CLIENTE-1", request);

        assertEquals(201, first.status().value());
        assertEquals(200, retry.status().value());
        assertEquals(first.reserva(), retry.reserva());
        assertEquals(1, reservaRepository.count());
        assertEquals(1, cantidadesRepository.findByUsuarioIdAndEventoId("CLIENTE-1", "EVT-RETRY")
                .orElseThrow().getCantidadReservada());
    }

    @Test
    void differentContentOrDifferentEventForSameClientAndRequestIdConflictsWithoutChanges() {
        inicializar("EVT-A", 4, true, false);
        inicializar("EVT-B", 4, true, false);
        CrearReservaRequest firstRequest = request("SOL-REUSE", seat("SEC-NUM", 1, 5));
        var first = reservaService.crear("EVT-A", "CLIENTE-1", firstRequest);

        assertThrows(ReservaConflictException.class,
                () -> reservaService.crear("EVT-A", "CLIENTE-1", request("SOL-REUSE", seat("SEC-NUM", 2, 6))));
        assertThrows(ReservaConflictException.class,
                () -> reservaService.crear("EVT-B", "CLIENTE-1", firstRequest));
        assertThrows(ReservaConflictException.class,
                () -> reservaService.crear("EVT-NO-INICIALIZADO", "CLIENTE-1", firstRequest));

        assertEquals(1, reservaRepository.count());
        assertEquals(1, cantidadesRepository.findByUsuarioIdAndEventoId("CLIENTE-1", "EVT-A")
                .orElseThrow().getCantidadReservada());
        assertEquals(0, cantidadesRepository.countByUsuarioIdAndEventoId("CLIENTE-1", "EVT-B"));
        assertNotEquals("LIBRE", seatState("EVT-A", "SEC-NUM", 1, 5));
        assertEquals("LIBRE", seatState("EVT-B", "SEC-NUM", 1, 5));
        assertEquals(first.reserva().reservaId(), reservaRepository.findAll().getFirst().getId());
    }

    @Test
    void anotherClientCanUseTheSameRequestIdIndependently() {
        inicializar("EVT-USERS", 4, true, false);

        var first = reservaService.crear("EVT-USERS", "CLIENTE-1", request("SOL-SHARED", seat("SEC-NUM", 1, 5)));
        var second = reservaService.crear("EVT-USERS", "CLIENTE-2", request("SOL-SHARED", seat("SEC-NUM", 2, 6)));

        assertEquals(201, first.status().value());
        assertEquals(201, second.status().value());
        assertEquals(2, reservaRepository.count());
    }

    @Test
    void expiredOrReleasedOriginalRequestCannotBeCreatedAgain() {
        inicializar("EVT-EXPIRED", 4, true, false);
        var first = reservaService.crear("EVT-EXPIRED", "CLIENTE-1",
                request("SOL-EXPIRED", seat("SEC-NUM", 1, 5)));
        jdbc.update("UPDATE reservas SET expira_en = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP) WHERE id = ?",
                first.reserva().reservaId());

        assertThrows(ReservaConflictException.class, () -> reservaService.crear("EVT-EXPIRED", "CLIENTE-1",
                request("SOL-EXPIRED", seat("SEC-NUM", 1, 5))));
        assertEquals(1, reservaRepository.count());
        assertEquals(1, cantidadesRepository.findByUsuarioIdAndEventoId("CLIENTE-1", "EVT-EXPIRED")
                .orElseThrow().getCantidadReservada());

        jdbc.update("UPDATE reservas SET estado = 'LIBERADA' WHERE id = ?", first.reserva().reservaId());
        jdbc.update("UPDATE asientos_disponibilidad SET estado = 'LIBRE' WHERE evento_id='EVT-EXPIRED'");
        jdbc.update("UPDATE cantidades_cliente_evento SET cantidad_reservada = 0 WHERE usuario_id='CLIENTE-1'");
        assertThrows(ReservaConflictException.class, () -> reservaService.crear("EVT-EXPIRED", "CLIENTE-1",
                request("SOL-EXPIRED", seat("SEC-NUM", 1, 5))));
    }

    @Test
    void failedMixedReservationRollsBackAllInventoryReservationAndAggregateChanges() {
        inicializar("EVT-ROLLBACK", 4, true, true);
        reservaService.crear("EVT-ROLLBACK", "OTRO", request("SOL-TAKEN", seat("SEC-NUM", 1, 5)));

        assertThrows(ReservaConflictException.class, () -> reservaService.crear("EVT-ROLLBACK", "CLIENTE-1",
                request("SOL-ROLLBACK", seat("SEC-NUM", 2, 6), seat("SEC-NUM", 1, 5), quantity("SEC-GEN", 2))));

        assertEquals("LIBRE", seatState("EVT-ROLLBACK", "SEC-NUM", 2, 6));
        assertEquals(0, jdbc.queryForObject("SELECT cantidad_reservada FROM sectores_disponibilidad "
                + "WHERE evento_id='EVT-ROLLBACK' AND sector_id='SEC-GEN'", Integer.class));
        assertEquals(0, cantidadesRepository.countByUsuarioIdAndEventoId("CLIENTE-1", "EVT-ROLLBACK"));
        assertEquals(1, reservaRepository.count());
    }

    @Test
    void failureAfterNumberedSeatUpdateRollsBackTheWholeReservationAndAggregate() {
        inicializacionService.inicializar("EVT-ROLLBACK-AFTER-WRITE", new InicializarDisponibilidadRequest(4,
                List.of(
                        new SectorInicializacionRequest("SEC-NUM", TipoSector.NUMERADO, 1,
                                List.of(new ar.edu.unicen.ms_disponibilidad_reservas.dto.AsientoInicializacionRequest(1, 1))),
                        new SectorInicializacionRequest("SEC-GEN", TipoSector.NO_NUMERADO, 1, List.of()))));
        EventoDisponibilidad event = eventoRepository.findByEventoId("EVT-ROLLBACK-AFTER-WRITE").orElseThrow();
        event.setEstadoEvento(EstadoEvento.PUBLICADO);
        eventoRepository.saveAndFlush(event);

        assertThrows(ReservaConflictException.class, () -> reservaService.crear("EVT-ROLLBACK-AFTER-WRITE", "CLIENTE",
                request("SOL-ROLLBACK-AFTER-WRITE", seat("SEC-NUM", 1, 1), quantity("SEC-GEN", 2))));

        assertEquals(0, reservaRepository.count());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM detalles_reserva", Integer.class));
        assertEquals("LIBRE", seatState("EVT-ROLLBACK-AFTER-WRITE", "SEC-NUM", 1, 1));
        assertEquals(0, jdbc.queryForObject("SELECT cantidad_reservada FROM sectores_disponibilidad "
                + "WHERE evento_id='EVT-ROLLBACK-AFTER-WRITE' AND sector_id='SEC-GEN'", Integer.class));
        assertEquals(0, cantidadesRepository.countByUsuarioIdAndEventoId("CLIENTE", "EVT-ROLLBACK-AFTER-WRITE"));
    }

    @Test
    void enforcesPublishedUnsuspendedEventAndClientLimit() {
        inicializar("EVT-PENDING", 4, false, false);
        assertThrows(ReservaConflictException.class, () -> reservaService.crear("EVT-PENDING", "CLIENTE-1",
                request("SOL-PENDING", seat("SEC-NUM", 1, 5))));

        inicializar("EVT-LIMIT", 4, true, true);
        reservaService.crear("EVT-LIMIT", "CLIENTE-1", request("SOL-LIMIT-1",
                quantity("SEC-GEN", 4)));
        assertThrows(ReservaConflictException.class, () -> reservaService.crear("EVT-LIMIT", "CLIENTE-1",
                request("SOL-LIMIT-2", quantity("SEC-GEN", 1))));
        assertEquals(4, cantidadesRepository.findByUsuarioIdAndEventoId("CLIENTE-1", "EVT-LIMIT")
                .orElseThrow().getCantidadReservada());
    }

    @Test
    void rejectsSuspendedTerminalAndExhaustedEventsAndUnknownLocalAvailability() {
        inicializar("EVT-SUSPENDED", 4, true, false);
        EventoDisponibilidad suspended = eventoRepository.findByEventoId("EVT-SUSPENDED").orElseThrow();
        suspended.setVentaSuspendida(true);
        eventoRepository.saveAndFlush(suspended);
        assertThrows(ReservaConflictException.class, () -> reservaService.crear("EVT-SUSPENDED", "CLIENTE",
                request("SOL-SUSPENDED", seat("SEC-NUM", 1, 5))));

        EstadoEvento[] unavailable = { EstadoEvento.AGOTADO, EstadoEvento.CANCELADO, EstadoEvento.FINALIZADO };
        for (int index = 0; index < unavailable.length; index++) {
            final int caseIndex = index;
            String eventId = "EVT-STATE-" + caseIndex;
            inicializar(eventId, 4, true, false);
            EventoDisponibilidad event = eventoRepository.findByEventoId(eventId).orElseThrow();
            event.setEstadoEvento(unavailable[index]);
            eventoRepository.saveAndFlush(event);
            assertThrows(ReservaConflictException.class, () -> reservaService.crear(eventId, "CLIENTE",
                    request("SOL-STATE-" + caseIndex, seat("SEC-NUM", 1, 5))));
        }

        assertThrows(DisponibilidadNoRegistradaException.class, () -> reservaService.crear("EVT-MISSING", "CLIENTE",
                request("SOL-MISSING", seat("SEC-NUM", 1, 5))));
        assertEquals(0, reservaRepository.count());
    }

    @Test
    void rejectsInsufficientNonNumberedCapacityAndCountsConfirmedEntriesAgainstTheLimit() {
        inicializacionService.inicializar("EVT-CAPACITY", new InicializarDisponibilidadRequest(4,
                List.of(new SectorInicializacionRequest("SEC-GEN", TipoSector.NO_NUMERADO, 1, List.of()))));
        EventoDisponibilidad capacityEvent = eventoRepository.findByEventoId("EVT-CAPACITY").orElseThrow();
        capacityEvent.setEstadoEvento(EstadoEvento.PUBLICADO);
        eventoRepository.saveAndFlush(capacityEvent);
        assertThrows(ReservaConflictException.class, () -> reservaService.crear("EVT-CAPACITY", "CLIENTE",
                request("SOL-CAPACITY", quantity("SEC-GEN", 2))));
        assertEquals(0, reservaRepository.count());
        assertEquals(0, jdbc.queryForObject("SELECT cantidad_reservada FROM sectores_disponibilidad "
                + "WHERE evento_id='EVT-CAPACITY'", Integer.class));

        inicializar("EVT-CONFIRMED-LIMIT", 4, true, true);
        cantidadesRepository.saveAndFlush(new CantidadClienteEvento("CLIENTE", "EVT-CONFIRMED-LIMIT"));
        CantidadClienteEvento aggregate = cantidadesRepository
                .findByUsuarioIdAndEventoId("CLIENTE", "EVT-CONFIRMED-LIMIT").orElseThrow();
        aggregate.setCantidadConfirmada(4);
        cantidadesRepository.saveAndFlush(aggregate);
        assertThrows(ReservaConflictException.class, () -> reservaService.crear("EVT-CONFIRMED-LIMIT", "CLIENTE",
                request("SOL-CONFIRMED-LIMIT", seat("SEC-NUM", 1, 5))));
        assertEquals(0, reservaRepository.count());
        assertEquals("LIBRE", seatState("EVT-CONFIRMED-LIMIT", "SEC-NUM", 1, 5));
    }

    @Test
    void limitCountsReservationsAndConfirmedSalesTogetherAndIsScopedByClientAndEvent() {
        inicializar("EVT-LIMIT-TRANSITIONS", 4, true, true);
        var firstHold = reservaService.crear("EVT-LIMIT-TRANSITIONS", "CLIENTE-1",
                request("SOL-LIMIT-HOLD", quantity("SEC-GEN", 3)));

        assertThrows(ReservaConflictException.class, () -> reservaService.crear("EVT-LIMIT-TRANSITIONS", "CLIENTE-1",
                request("SOL-LIMIT-OVER-HOLD", quantity("SEC-GEN", 2))));
        var fourth = reservaService.crear("EVT-LIMIT-TRANSITIONS", "CLIENTE-1",
                request("SOL-LIMIT-FOURTH", quantity("SEC-GEN", 1)));
        assertEquals(201, fourth.status().value());

        reservaService.confirmar(firstHold.reserva().reservaId());
        assertEquals(1, cantidadesRepository.findByUsuarioIdAndEventoId("CLIENTE-1", "EVT-LIMIT-TRANSITIONS")
                .orElseThrow().getCantidadReservada());
        assertEquals(3, cantidadesRepository.findByUsuarioIdAndEventoId("CLIENTE-1", "EVT-LIMIT-TRANSITIONS")
                .orElseThrow().getCantidadConfirmada());
        assertThrows(ReservaConflictException.class, () -> reservaService.crear("EVT-LIMIT-TRANSITIONS", "CLIENTE-1",
                request("SOL-LIMIT-OVER-CONFIRMED", quantity("SEC-GEN", 1))));
        reservaService.confirmar(fourth.reserva().reservaId());
        assertThrows(ReservaConflictException.class, () -> reservaService.crear("EVT-LIMIT-TRANSITIONS", "CLIENTE-1",
                request("SOL-LIMIT-OVER-ALL-CONFIRMED", quantity("SEC-GEN", 1))));

        // A different customer has an independent limit on this event.
        assertEquals(201, reservaService.crear("EVT-LIMIT-TRANSITIONS", "CLIENTE-2",
                request("SOL-LIMIT-OTHER-CLIENT", quantity("SEC-GEN", 1))).status().value());

        // The same customer's limit is independent on a different event.
        inicializar("EVT-LIMIT-OTHER-EVENT", 4, true, false);
        assertEquals(201, reservaService.crear("EVT-LIMIT-OTHER-EVENT", "CLIENTE-1",
                request("SOL-LIMIT-OTHER-EVENT", seat("SEC-NUM", 1, 5), seat("SEC-NUM", 2, 6))).status().value());
    }

    @Test
    void rejectsMalformedDetailShapesAndIdentifiersLongerThanThePersistentColumns() {
        inicializar("EVT-VALIDATION", 4, true, false);
        assertThrows(IllegalArgumentException.class, () -> reservaService.crear("EVT-VALIDATION", "CLIENTE",
                request("SOL-BAD", new DetalleReservaSolicitud("SEC-NUM", 1, 5, 1))));
        assertThrows(IllegalArgumentException.class, () -> reservaService.crear("EVT-VALIDATION", "CLIENTE",
                request("SOL-BAD2", new DetalleReservaSolicitud("SEC-NUM", null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> reservaService.crear("EVT-VALIDATION", "CLIENTE",
                request("x".repeat(256), seat("SEC-NUM", 1, 5))));
        assertThrows(IllegalArgumentException.class, () -> reservaService.crear("EVT-VALIDATION", "u".repeat(256),
                request("SOL-LONG-USER", seat("SEC-NUM", 1, 5))));
        assertEquals(0, reservaRepository.count());
        assertEquals("LIBRE", seatState("EVT-VALIDATION", "SEC-NUM", 1, 5));
    }

    @Test
    void acceptsMaximumApprovedLengthForRequestAndUserIdentifiers() {
        inicializar("EVT-LENGTH", 4, true, false);
        String requestId = "s".repeat(255);
        String userId = "u".repeat(255);

        var result = reservaService.crear("EVT-LENGTH", userId, request(requestId, seat("SEC-NUM", 1, 5)));

        assertEquals(201, result.status().value());
        assertEquals(255, reservaRepository.findAll().getFirst().getClaveIdempotencia().length());
        assertEquals(255, reservaRepository.findAll().getFirst().getUsuarioId().length());
    }

    @Test
    void concurrentRequestsCannotCreateTwoReservationsForTheSameSeat() throws Exception {
        inicializar("EVT-CONCURRENT", 4, true, false);
        CrearReservaRequest first = request("SOL-CONCURRENT-1", seat("SEC-NUM", 1, 5));
        CrearReservaRequest second = request("SOL-CONCURRENT-2", seat("SEC-NUM", 1, 5));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> one = executor.submit(() -> reserveAfterBarrier(ready, start,
                    () -> reservaService.crear("EVT-CONCURRENT", "CLIENTE-1", first)));
            Future<Boolean> two = executor.submit(() -> reserveAfterBarrier(ready, start,
                    () -> reservaService.crear("EVT-CONCURRENT", "CLIENTE-2", second)));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))
                    .stream().filter(Boolean::booleanValue).count());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, reservaRepository.count());
        assertEquals("RESERVADO", seatState("EVT-CONCURRENT", "SEC-NUM", 1, 5));
    }

    @Test
    void concurrentIdenticalRetriesReturnOneCreatedAndOneExistingReservation() throws Exception {
        inicializar("EVT-CONCURRENT-RETRY", 4, true, false);
        CrearReservaRequest sameRequest = request("SOL-CONCURRENT-RETRY", seat("SEC-NUM", 1, 5));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> one = executor.submit(() -> createAfterBarrier(ready, start, sameRequest));
            Future<Integer> two = executor.submit(() -> createAfterBarrier(ready, start, sameRequest));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(List.of(200, 201), List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))
                    .stream().sorted().toList());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, reservaRepository.count());
        assertEquals(1, cantidadesRepository.findByUsuarioIdAndEventoId("CLIENTE-1", "EVT-CONCURRENT-RETRY")
                .orElseThrow().getCantidadReservada());
    }

    private void inicializar(String eventoId, int limite, boolean publicar, boolean incluirNoNumerado) {
        List<SectorInicializacionRequest> sectores = new java.util.ArrayList<>();
        sectores.add(new SectorInicializacionRequest("SEC-NUM", TipoSector.NUMERADO, 4,
                List.of(new ar.edu.unicen.ms_disponibilidad_reservas.dto.AsientoInicializacionRequest(1, 5),
                        new ar.edu.unicen.ms_disponibilidad_reservas.dto.AsientoInicializacionRequest(2, 6),
                        new ar.edu.unicen.ms_disponibilidad_reservas.dto.AsientoInicializacionRequest(3, 7),
                        new ar.edu.unicen.ms_disponibilidad_reservas.dto.AsientoInicializacionRequest(4, 8))));
        if (incluirNoNumerado) {
            sectores.add(new SectorInicializacionRequest("SEC-GEN", TipoSector.NO_NUMERADO, 10, List.of()));
        }
        inicializacionService.inicializar(eventoId, new InicializarDisponibilidadRequest(limite, sectores));
        if (publicar) {
            EventoDisponibilidad event = eventoRepository.findByEventoId(eventoId).orElseThrow();
            event.setEstadoEvento(EstadoEvento.PUBLICADO);
            eventoRepository.saveAndFlush(event);
        }
    }

    private boolean reserveAfterBarrier(CountDownLatch ready, CountDownLatch start,
            java.util.concurrent.Callable<ResultadoCreacionReserva> operation) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            operation.call();
            return true;
        } catch (ReservaConflictException expected) {
            return false;
        }
    }

    private int createAfterBarrier(CountDownLatch ready, CountDownLatch start,
            CrearReservaRequest request) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return reservaService.crear("EVT-CONCURRENT-RETRY", "CLIENTE-1", request).status().value();
    }

    private String seatState(String event, String sector, int row, int number) {
        return jdbc.queryForObject("SELECT estado FROM asientos_disponibilidad "
                + "WHERE evento_id=? AND sector_id=? AND fila=? AND numero=?", String.class,
                event, sector, row, number);
    }

    private CrearReservaRequest request(String id, DetalleReservaSolicitud... details) {
        return new CrearReservaRequest(id, List.of(details));
    }

    private DetalleReservaSolicitud seat(String sector, int row, int number) {
        return new DetalleReservaSolicitud(sector, row, number, null);
    }

    private DetalleReservaSolicitud quantity(String sector, int quantity) {
        return new DetalleReservaSolicitud(sector, null, null, quantity);
    }
}
