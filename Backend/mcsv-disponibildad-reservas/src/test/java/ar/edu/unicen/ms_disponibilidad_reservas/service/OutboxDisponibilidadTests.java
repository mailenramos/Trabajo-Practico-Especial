package ar.edu.unicen.ms_disponibilidad_reservas.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ar.edu.unicen.ms_disponibilidad_reservas.dto.AsientoInicializacionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.CrearReservaRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.DetalleDevolucionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.DetalleReservaSolicitud;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.EstadoVenta;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.InicializarDisponibilidadRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.LiberarReservaRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.SectorInicializacionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoNotificacionOutbox;
import ar.edu.unicen.ms_disponibilidad_reservas.model.NotificacionDisponibilidadOutbox;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoAsiento;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.NotificacionDisponibilidadOutboxRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:availability-outbox;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false",
        "ms2.outbox.fixed-delay-ms=3600000"
})
@DirtiesContext
class OutboxDisponibilidadTests {

    private static final AtomicInteger HTTP_STATUS = new AtomicInteger(204);
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> LAST_PATH = new AtomicReference<>();
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> REQUEST_GATE = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> REQUEST_STARTED = new AtomicReference<>(new CountDownLatch(1));
    private static final HttpServer HTTP_SERVER = startServer();

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OutboxDisponibilidadService outboxService;
    @Autowired private NotificacionDisponibilidadOutboxRepository outboxRepository;
    @Autowired private InicializacionDisponibilidadService inicializacionService;
    @Autowired private EstadoVentaService estadoVentaService;
    @Autowired private ReservaService reservaService;
    @Autowired private DevolucionService devolucionService;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("ms1.api.base-url", () -> "http://localhost:" + HTTP_SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        HTTP_SERVER.stop(0);
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM notificaciones_disponibilidad_outbox");
        jdbcTemplate.update("DELETE FROM devolucion_detalles_cantidades");
        jdbcTemplate.update("DELETE FROM devolucion_detalles_asientos");
        jdbcTemplate.update("DELETE FROM devoluciones");
        jdbcTemplate.update("DELETE FROM detalles_reserva");
        jdbcTemplate.update("DELETE FROM reservas");
        jdbcTemplate.update("DELETE FROM cantidades_cliente_evento");
        jdbcTemplate.update("DELETE FROM asientos_disponibilidad");
        jdbcTemplate.update("DELETE FROM sectores_disponibilidad");
        jdbcTemplate.update("DELETE FROM eventos_disponibilidad");
        HTTP_STATUS.set(204);
        REQUESTS.set(0);
        LAST_PATH.set(null);
        LAST_BODY.set(null);
        REQUEST_GATE.set(null);
        REQUEST_STARTED.set(new CountDownLatch(1));
    }

    @Test
    void temporaryReservationDoesNotCreateSoldOutNotificationButConfirmationDoesAtomically() {
        String reservaId = crearReservaNumerada("outbox-confirmar");

        assertEquals(0, outboxRepository.count());
        assertEquals(EstadoAsiento.RESERVADO, estadoAsiento("EVT-outbox-confirmar"));

        reservaService.confirmar(reservaId);

        assertEquals(1, outboxRepository.count());
        reservaService.confirmar(reservaId);
        assertEquals(1, outboxRepository.count());
        NotificacionDisponibilidadOutbox notification = outboxRepository.findAll().getFirst();
        assertTrue(notification.isAgotado());
        assertEquals(EstadoNotificacionOutbox.PENDIENTE, notification.getEstado());
        assertEquals(EstadoAsiento.VENDIDO, estadoAsiento("EVT-outbox-confirmar"));
    }

    @Test
    void transactionRollbackRevertsConfirmationInventoryAndOutboxTogether() {
        String reservaId = crearReservaNumerada("outbox-rollback");
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        try {
            outer.execute(status -> {
                reservaService.confirmar(reservaId);
                throw new IllegalStateException("forzar rollback de la transacción externa");
            });
        } catch (IllegalStateException expected) {
            assertEquals("forzar rollback de la transacción externa", expected.getMessage());
        }

        assertEquals(0, outboxRepository.count());
        assertEquals(EstadoAsiento.RESERVADO, estadoAsiento("EVT-outbox-rollback"));
        assertEquals("VIGENTE", jdbcTemplate.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, reservaId));
    }

    @Test
    void partialRefundRestoresAvailabilityWithoutReactivatingSuspendedSales() {
        String eventoId = "EVT-outbox-refund";
        String reservaId = crearReservaNumerada("outbox-refund");
        reservaService.confirmar(reservaId);
        estadoVentaService.aplicar(eventoId, EstadoVenta.SUSPENDIDA);

        devolucionService.devolverAprobada(reservaId, new LiberarReservaRequest(
                "DEVOLUCION_APROBADA", UUID.randomUUID().toString(),
                List.of(new DetalleDevolucionRequest("SEC", 1, 1, null))));

        List<NotificacionDisponibilidadOutbox> rows = outboxRepository.findAll().stream()
                .sorted((a, b) -> a.getId().compareTo(b.getId())).toList();
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).isAgotado());
        assertFalse(rows.get(1).isAgotado());
        assertEquals("PUBLICADO", jdbcTemplate.queryForObject(
                "SELECT estado_evento FROM eventos_disponibilidad WHERE evento_id=?", String.class, eventoId));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT venta_suspendida FROM eventos_disponibilidad WHERE evento_id=?", Boolean.class, eventoId));
        assertEquals(EstadoAsiento.LIBRE, estadoAsiento(eventoId));
    }

    @Test
    void notificationSuccessIsPersistedAndUsesAgreedMs1RouteAndJson() {
        crearNotificacion("EVT-http-ok", true);

        assertEquals(1, outboxService.procesarPendientes());

        NotificacionDisponibilidadOutbox sent = outboxRepository.findAll().getFirst();
        assertEquals(EstadoNotificacionOutbox.ENVIADA, sent.getEstado());
        assertEquals(1, sent.getIntentos());
        assertEquals(1, REQUESTS.get());
        assertEquals("/eventos/EVT-http-ok/disponibilidad", LAST_PATH.get());
        assertTrue(LAST_BODY.get().contains("\"agotado\":true"));
    }

    @Test
    void ms1ErrorKeepsNotificationDurableAndItCanBeRetried() {
        HTTP_STATUS.set(503);
        crearNotificacion("EVT-http-retry", false);

        assertEquals(1, outboxService.procesarPendientes());

        NotificacionDisponibilidadOutbox pending = outboxRepository.findAll().getFirst();
        assertEquals(EstadoNotificacionOutbox.PENDIENTE, pending.getEstado());
        assertEquals(1, pending.getIntentos());
        assertEquals("HTTP_503", pending.getUltimoError());
        assertTrue(pending.getProximoIntentoEn().isAfter(pending.getCreadaEn()));

        HTTP_STATUS.set(204);
        jdbcTemplate.update("UPDATE notificaciones_disponibilidad_outbox SET proximo_intento_en=? WHERE id=?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), pending.getId());
        assertEquals(1, outboxService.procesarPendientes());
        assertEquals(EstadoNotificacionOutbox.ENVIADA,
                outboxRepository.findById(pending.getId()).orElseThrow().getEstado());
        assertEquals(2, REQUESTS.get());
    }

    @Test
    void staleClaimIsRecoveredAfterAWorkerRestart() {
        NotificacionDisponibilidadOutbox stale = crearNotificacion("EVT-stale-lease", true);
        stale.reclamar("worker-anterior", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        outboxRepository.saveAndFlush(stale);

        assertEquals(1, outboxService.procesarPendientes());

        NotificacionDisponibilidadOutbox sent = outboxRepository.findById(stale.getId()).orElseThrow();
        assertEquals(EstadoNotificacionOutbox.ENVIADA, sent.getEstado());
        assertEquals(2, sent.getIntentos());
    }

    @Test
    void twoConcurrentWorkersDoNotSendTheSameNotificationAtOnce() throws Exception {
        crearNotificacion("EVT-concurrent-worker", true);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        REQUEST_GATE.set(releaseRequest);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(outboxService::procesarPendientes);
            assertTrue(REQUEST_STARTED.get().await(3, TimeUnit.SECONDS));
            var second = executor.submit(outboxService::procesarPendientes);
            Thread.sleep(150);
            assertEquals(1, REQUESTS.get());
            releaseRequest.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, REQUESTS.get());
        assertEquals(EstadoNotificacionOutbox.ENVIADA, outboxRepository.findAll().getFirst().getEstado());
    }

    private String crearReservaNumerada(String suffix) {
        String eventoId = "EVT-" + suffix;
        inicializacionService.inicializar(eventoId, new InicializarDisponibilidadRequest(4, List.of(
                new SectorInicializacionRequest("SEC", ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector.NUMERADO,
                        1, List.of(new AsientoInicializacionRequest(1, 1))))));
        estadoVentaService.aplicar(eventoId, EstadoVenta.ACTIVA);
        return reservaService.crear(eventoId, "CLIENTE-" + suffix,
                new CrearReservaRequest("SOL-" + suffix,
                        List.of(new DetalleReservaSolicitud("SEC", 1, 1, null)))).reserva().reservaId();
    }

    private EstadoAsiento estadoAsiento(String eventoId) {
        String estado = jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id=?", String.class, eventoId);
        return EstadoAsiento.valueOf(estado);
    }

    private NotificacionDisponibilidadOutbox crearNotificacion(String eventoId, boolean agotado) {
        return outboxRepository.saveAndFlush(new NotificacionDisponibilidadOutbox(
                eventoId, agotado, LocalDateTime.now(ZoneOffset.UTC)));
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                REQUESTS.incrementAndGet();
                LAST_PATH.set(exchange.getRequestURI().getPath());
                LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                CountDownLatch gate = REQUEST_GATE.get();
                if (gate != null) {
                    REQUEST_STARTED.get().countDown();
                    try {
                        gate.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                exchange.sendResponseHeaders(HTTP_STATUS.get(), -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

}
