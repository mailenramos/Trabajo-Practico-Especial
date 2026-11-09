package ar.edu.unicen.ms_disponibilidad_reservas.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeast;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.flywaydb.core.Flyway;

import ar.edu.unicen.ms_disponibilidad_reservas.model.AsientoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.CantidadClienteEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.DetalleReserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.EventoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.Reserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.SectorDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.AsientoInicializacionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.CrearReservaRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.LiberarReservaRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.DetalleReservaSolicitud;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.EstadoVenta;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.InicializarDisponibilidadRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.SectorInicializacionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.AsientoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.CantidadClienteEventoRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.EventoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.SectorDisponibilidadRepository;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "ms2.outbox.fixed-delay-ms=3600000"
})
@DirtiesContext
@EnabledIfSystemProperty(named = "ms2.mysql.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DisponibilidadMySqlConcurrencyTests {

    private static TemporaryDatabase temporaryDatabase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DisponibilidadService disponibilidadService;

    @Autowired
    private InicializacionDisponibilidadService inicializacionService;

    @Autowired
    private AsientoDisponibilidadRepository asientoRepository;

    @Autowired
    private SectorDisponibilidadRepository sectorRepository;

    @Autowired
    private CantidadClienteEventoService cantidadesService;

    @MockitoSpyBean
    private ar.edu.unicen.ms_disponibilidad_reservas.repository.ReservaRepository reservaRepository;

    @Autowired
    private ReservaService reservaService;

    @Autowired
    private DevolucionService devolucionService;

    @Autowired
    private EstadoVentaService estadoVentaService;

    @Autowired
    private CantidadClienteEventoRepository cantidadesRepository;

    @Autowired
    private EventoDisponibilidadRepository eventosRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void configureTemporaryMySqlSchema(DynamicPropertyRegistry registry) {
        temporaryDatabase = TemporaryDatabase.createFromApplicationConfiguration();
        registry.add("spring.datasource.url", () -> temporaryDatabase.jdbcUrl);
        registry.add("spring.datasource.username", () -> temporaryDatabase.username);
        registry.add("spring.datasource.password", () -> temporaryDatabase.password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
    }

    @Test
    void v7PreservesHistoricalFingerprintsAndRowsButStopsMatchingCaseAliases() throws Exception {
        TemporaryDatabase isolated = TemporaryDatabase.createFromApplicationConfiguration();
        try {
            Flyway.configure().dataSource(isolated.jdbcUrl, isolated.username, isolated.password)
                    .locations("classpath:db/migration").target("6").load().migrate();
            String initHash = "v2:" + "a".repeat(64);
            String requestHash = "v1:" + "b".repeat(64);
            String id = UUID.randomUUID().toString();
            try (Connection c = DriverManager.getConnection(isolated.jdbcUrl, isolated.username, isolated.password)) {
                try (var st = c.prepareStatement("INSERT INTO eventos_disponibilidad "
                        + "(evento_id,estado_evento,fecha_creacion,venta_suspendida,limite_entradas_por_cliente,huella_inicializacion) "
                        + "VALUES ('EVT-HISTORY','PUBLICADO',CURRENT_TIMESTAMP,0,6,?)")) {
                    st.setString(1, initHash); st.executeUpdate();
                }
                try (var st = c.prepareStatement("INSERT INTO reservas "
                        + "(id,evento_id,usuario_id,clave_idempotencia,estado,expira_en,huella_solicitud) "
                        + "VALUES (?,'EVT-HISTORY','CLIENT-HISTORY','SOL-HISTORY','VIGENTE',CURRENT_TIMESTAMP,?)")) {
                    st.setString(1, id); st.setString(2, requestHash); st.executeUpdate();
                }
                try (var st = c.createStatement(); var rows = st.executeQuery(
                        "SELECT COUNT(*) FROM reservas WHERE usuario_id='client-history' AND clave_idempotencia='sol-history'")) {
                    rows.next(); assertEquals(1, rows.getInt(1));
                }
            }
            Flyway.configure().dataSource(isolated.jdbcUrl, isolated.username, isolated.password)
                    .locations("classpath:db/migration").target("7").load().migrate();
            try (Connection c = DriverManager.getConnection(isolated.jdbcUrl, isolated.username, isolated.password);
                    var st = c.createStatement()) {
                try (var rows = st.executeQuery("SELECT id,huella_solicitud FROM reservas")) {
                    assertTrue(rows.next()); assertEquals(id, rows.getString(1));
                    assertEquals(requestHash, rows.getString(2)); assertTrue(!rows.next());
                }
                try (var rows = st.executeQuery("SELECT huella_inicializacion,limite_entradas_por_cliente FROM eventos_disponibilidad")) {
                    assertTrue(rows.next()); assertEquals(initHash, rows.getString(1)); assertEquals(6, rows.getInt(2));
                }
                try (var rows = st.executeQuery(
                        "SELECT COUNT(*) FROM reservas WHERE usuario_id='client-history' AND clave_idempotencia='sol-history'")) {
                    rows.next(); assertEquals(0, rows.getInt(1));
                }
            }
        } finally { isolated.drop(); }
    }

    @Test
    void differentEventLimitsArePersistedEnforcedAndCountConfirmedSales() {
        for (int limit : List.of(1, 4, 6)) {
            String event = "EVENT-LIMIT-" + limit;
            inicializacionService.inicializar(event, new InicializarDisponibilidadRequest(limit, List.of(
                    new SectorInicializacionRequest("SEC", TipoSector.NO_NUMERADO, 20, List.of()))));
            estadoVentaService.aplicar(event, EstadoVenta.ACTIVA);
            assertEquals(limit, eventosRepository.findByEventoId(event).orElseThrow().getLimiteEntradasPorCliente());
            var first = reservaService.crear(event, "SAME-OWNER", requestCantidad("FIRST-" + limit, "SEC", limit));
            assertThrows(ReservaConflictException.class,
                    () -> reservaService.crear(event, "SAME-OWNER", requestCantidad("EXCESS-" + limit, "SEC", 1)));
            reservaService.confirmar(first.reserva().reservaId());
            assertThrows(ReservaConflictException.class,
                    () -> reservaService.crear(event, "SAME-OWNER", requestCantidad("SOLD-EXCESS-" + limit, "SEC", 1)));
            assertThrows(InicializacionConflictException.class, () -> inicializacionService.inicializar(event,
                    new InicializarDisponibilidadRequest(limit + 1, List.of(
                            new SectorInicializacionRequest("SEC", TipoSector.NO_NUMERADO, 20, List.of())))));
            assertEquals(limit, eventosRepository.findByEventoId(event).orElseThrow().getLimiteEntradasPorCliente());
        }
    }

    @Test
    void exactExternalIdsAndIdempotencyKeysPreserveCaseAccentsAndSpaces() {
        List<String> variants = List.of("EXACT", "exact", "ÉXACT", "EXACT ");
        for (String event : variants) {
            inicializacionService.inicializar(event, new InicializarDisponibilidadRequest(4, List.of(
                    new SectorInicializacionRequest("SEC", TipoSector.NO_NUMERADO, 20, List.of()),
                    new SectorInicializacionRequest("sec", TipoSector.NO_NUMERADO, 20, List.of()),
                    new SectorInicializacionRequest("SÉC", TipoSector.NO_NUMERADO, 20, List.of()),
                    new SectorInicializacionRequest("SEC ", TipoSector.NO_NUMERADO, 20, List.of()))));
            estadoVentaService.aplicar(event, EstadoVenta.ACTIVA);
            assertEquals(event, eventosRepository.findByEventoId(event).orElseThrow().getEventoId());
            assertEquals(4, sectorRepository.findAllByEventoId(event).size());
        }
        for (String client : variants) {
            var created = reservaService.crear("EXACT", client, requestCantidad("SOL", "SEC", 1));
            assertEquals(org.springframework.http.HttpStatus.CREATED, created.status());
            assertEquals(client, reservaService.consultar(created.reserva().reservaId()).clienteId());
            assertEquals(org.springframework.http.HttpStatus.OK,
                    reservaService.crear("EXACT", client, requestCantidad("SOL", "SEC", 1)).status());
        }
        for (String key : variants) {
            assertEquals(org.springframework.http.HttpStatus.CREATED,
                    reservaService.crear("exact", "KEY-OWNER", requestCantidad(key, "sec", 1)).status());
        }
        assertEquals("utf8mb4_0900_bin", jdbcTemplate.queryForObject(
                "SELECT COLLATION_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='reservas' AND COLUMN_NAME='clave_idempotencia'", String.class));
    }

    @Test
    void configuredServerIsMySql8413() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version.startsWith("8.4.13"), "Se esperaba MySQL 8.4.13 y se obtuvo " + version);
    }

    @Test
    void flywayMigratesBlankSchemaAndHibernateValidatesIt() {
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7"), jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                String.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='devoluciones'", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='devoluciones' AND INDEX_NAME='PRIMARY' "
                + "AND COLUMN_NAME='id' AND NON_UNIQUE=0", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='devolucion_detalles_asientos' "
                + "AND CONSTRAINT_NAME='uk_devolucion_asiento_detalle_original' AND CONSTRAINT_TYPE='UNIQUE'",
                Integer.class));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='devolucion_detalles_cantidades' "
                + "AND CONSTRAINT_TYPE='FOREIGN KEY'", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='devoluciones' "
                + "AND CONSTRAINT_NAME='fk_devolucion_reserva' AND CONSTRAINT_TYPE='FOREIGN KEY'", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='devolucion_detalles_cantidades' "
                + "AND CONSTRAINT_NAME='uk_devolucion_cantidad_detalle' AND CONSTRAINT_TYPE='UNIQUE'", Integer.class));
        assertEquals(1, enforcedCheckConstraintCount(
                "devolucion_detalles_cantidades", "ck_devolucion_cantidad_positiva"));
        assertEquals("int", jdbcTemplate.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'asientos_disponibilidad' AND COLUMN_NAME = 'fila'",
                String.class));
        assertEquals("int", jdbcTemplate.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'detalles_reserva' AND COLUMN_NAME = 'fila'",
                String.class));
        assertEquals("YES", jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'detalles_reserva' AND COLUMN_NAME = 'fila'",
                String.class));
        assertEquals("varchar", jdbcTemplate.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'eventos_disponibilidad' "
                        + "AND COLUMN_NAME = 'estado_evento'", String.class));
        assertEquals(1, enforcedCheckConstraintCount("eventos_disponibilidad", "ck_evento_estado"));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notificaciones_disponibilidad_outbox'", Integer.class));
        assertEquals(1, enforcedCheckConstraintCount(
                "notificaciones_disponibilidad_outbox", "ck_outbox_estado_envio"));
        assertEquals(1, enforcedCheckConstraintCount("sectores_disponibilidad", "ck_sector_capacidad_disponible"));
        // El arranque exitoso de este contexto con ddl-auto=validate comprueba el mapeo completo.
    }

    @Test
    void baselineVersionOneAdoptsLegacySchemaAndRunsOnlyLaterMigrations() throws Exception {
        TemporaryDatabase legacy = TemporaryDatabase.createFromApplicationConfiguration();
        try {
            try (Connection connection = DriverManager.getConnection(legacy.jdbcUrl, legacy.username,
                    legacy.password)) {
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/V1__crear_esquema_base.sql"));
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO eventos_disponibilidad "
                            + "(estado_evento, evento_id, fecha_creacion, venta_suspendida) "
                            + "VALUES ('PUBLICADO', 'evento-heredado', CURRENT_TIMESTAMP, 0)");
                }
            }

            Flyway flyway = Flyway.configure()
                    .dataSource(legacy.jdbcUrl, legacy.username, legacy.password)
                    .locations("classpath:db/migration")
                    .baselineVersion("1")
                    .load();
            flyway.baseline();
            flyway.migrate();

            try (Connection connection = DriverManager.getConnection(legacy.jdbcUrl, legacy.username,
                    legacy.password);
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT estado_evento FROM eventos_disponibilidad "
                            + "WHERE evento_id = 'evento-heredado'")) {
                assertTrue(result.next());
                assertEquals("PUBLICADO", result.getString(1));
            }
            try (Connection connection = DriverManager.getConnection(legacy.jdbcUrl, legacy.username,
                    legacy.password);
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT version FROM flyway_schema_history "
                            + "WHERE success = 1 ORDER BY installed_rank")) {
                List<String> versions = new java.util.ArrayList<>();
                while (result.next()) {
                    versions.add(result.getString(1));
                }
                assertEquals(List.of("1", "2", "3", "4", "5", "6", "7"), versions);
            }
        } finally {
            legacy.drop();
        }
    }

    @Test
    void mysqlPersistsEveryEventStatusAndRejectsUnknownStatus() {
        int index = 0;
        for (EstadoEvento estado : EstadoEvento.values()) {
            EventoDisponibilidad evento = new EventoDisponibilidad();
            evento.setEventoId("evento-estado-" + UUID.randomUUID());
            evento.setEstadoEvento(estado);
            eventosRepository.saveAndFlush(evento);
            entityManager.clear();
            assertEquals(estado, eventosRepository.findByEventoId(evento.getEventoId()).orElseThrow().getEstadoEvento());
            index++;
        }
        assertEquals(EstadoEvento.values().length, index);

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO eventos_disponibilidad "
                        + "(estado_evento, evento_id, fecha_creacion, venta_suspendida) "
                        + "VALUES ('NO_VALIDO', ?, CURRENT_TIMESTAMP, 0)",
                "evento-estado-invalido-" + UUID.randomUUID()));
    }

    @Test
    void mysqlEventStatusCheckIsCaseSensitiveAndAcceptsCanonicalPublished() {
        assertEventStatusInsertRejected("publicado");
        assertEventStatusInsertRejected("Publicado");

        String eventoId = "evento-publicado-canonico-" + UUID.randomUUID();
        assertEquals(1, jdbcTemplate.update(
                "INSERT INTO eventos_disponibilidad "
                        + "(estado_evento, evento_id, fecha_creacion, venta_suspendida) "
                        + "VALUES ('PUBLICADO', ?, CURRENT_TIMESTAMP, 0)",
                eventoId));
        assertEquals("PUBLICADO", jdbcTemplate.queryForObject(
                "SELECT estado_evento FROM eventos_disponibilidad WHERE evento_id = ?", String.class, eventoId));
    }

    @Test
    void generatedAndExistingCheckConstraintsArePresentAndEnforced() {
        assertEquals(1, enforcedCheckConstraintCount(
                "sectores_disponibilidad", "ck_sector_capacidad_disponible"));
        assertEquals(1, enforcedCheckConstraintCount(
                "asientos_disponibilidad", "asientos_disponibilidad_chk_1"));
        assertEquals(1, enforcedCheckConstraintCount("eventos_disponibilidad", "ck_evento_estado"));
    }

    @Test
    void mysqlRejectsNegativeReservedQuantity() {
        assertSectorInsertRejected(-1, 0, 10);
    }

    @Test
    void mysqlRejectsNegativeSoldQuantity() {
        assertSectorInsertRejected(0, -1, 10);
    }

    @Test
    void mysqlRejectsReservedAndSoldQuantitiesAboveCapacity() {
        assertSectorInsertRejected(6, 5, 10);
    }

    @Test
    void mysqlPersistsCompleteInitializationAndEffectiveDefaultLimit() {
        String eventoId = "evento-init-" + UUID.randomUUID();
        EventoDisponibilidad evento = inicializacionService.inicializar(eventoId, solicitudMixta(null));

        EventoDisponibilidad persistido = eventosRepository.findByEventoId(eventoId).orElseThrow();
        assertEquals(evento.getId(), persistido.getId());
        assertEquals(EstadoEvento.PENDIENTE_PUBLICACION, persistido.getEstadoEvento());
        assertEquals(4, persistido.getLimiteEntradasPorCliente());
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sectores_disponibilidad WHERE evento_id = ? AND tipo = 'NUMERADO'",
                Long.class, eventoId));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sectores_disponibilidad WHERE evento_id = ? AND tipo = 'NO_NUMERADO'",
                Long.class, eventoId));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM asientos_disponibilidad WHERE evento_id = ? AND estado = 'LIBRE'",
                Long.class, eventoId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada + cantidad_vendida FROM sectores_disponibilidad "
                        + "WHERE evento_id = ? AND sector_id = 'sector-general'",
                Integer.class, eventoId));
    }

    @Test
    void mysqlSeatCompositeBusinessKeyRejectsDuplicateRows() {
        String eventoId = "evento-key-" + UUID.randomUUID();
        String sectorId = "sector-key-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO asientos_disponibilidad "
                        + "(evento_id, sector_id, fila, numero, estado) VALUES (?, ?, 1, 1, 'LIBRE')",
                eventoId, sectorId);
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("INSERT INTO asientos_disponibilidad "
                        + "(evento_id, sector_id, fila, numero, estado) VALUES (?, ?, 1, 1, 'LIBRE')",
                eventoId, sectorId));
    }

    @Test
    void mysqlValidationFailureLeavesNoInitializationRows() {
        String eventoId = "evento-invalid-" + UUID.randomUUID();
        assertThrows(ConstraintViolationException.class,
                () -> inicializacionService.inicializar(eventoId, solicitudConNumeroInvalido()));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM eventos_disponibilidad WHERE evento_id = ?", Long.class, eventoId));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sectores_disponibilidad WHERE evento_id = ?", Long.class, eventoId));
    }

    @Test
    void mysqlIdenticalRetryReturnsExistingDataAndDifferentRetryConflicts() {
        String eventoId = "evento-idempotente-" + UUID.randomUUID();
        InicializarDisponibilidadRequest request = solicitudMixta(4);
        EventoDisponibilidad original = inicializacionService.inicializar(eventoId, request);
        EventoDisponibilidad identical = inicializacionService.inicializar(eventoId, request);
        assertEquals(original.getId(), identical.getId());

        InicializarDisponibilidadRequest changed = solicitudAlternativa(4);
        assertThrows(InicializacionConflictException.class,
                () -> inicializacionService.inicializar(eventoId, changed));
        assertEquals(4, eventosRepository.findByEventoId(eventoId).orElseThrow().getLimiteEntradasPorCliente());
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sectores_disponibilidad WHERE evento_id = ?", Long.class, eventoId));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM asientos_disponibilidad WHERE evento_id = ?", Long.class, eventoId));
    }

    @Test
    void mysqlVersionedFingerprintsRetryAndLegacyFingerprintsFailClosed() {
        String currentEventId = "evento-huella-v2-" + UUID.randomUUID();
        InicializarDisponibilidadRequest request = solicitudMixta(null);
        EventoDisponibilidad created = inicializacionService.inicializar(currentEventId, request);
        assertTrue(created.getHuellaInicializacion().matches("v2:[0-9a-f]{64}"));
        EventoDisponibilidad retry = inicializacionService.inicializar(currentEventId, request);
        assertEquals(created.getId(), retry.getId());

        String legacyEventId = "evento-huella-v1-" + UUID.randomUUID();
        EventoDisponibilidad legacy = new EventoDisponibilidad();
        legacy.setEventoId(legacyEventId);
        legacy.setHuellaInicializacion("a".repeat(64));
        eventosRepository.saveAndFlush(legacy);

        InicializacionConflictException conflict = assertThrows(InicializacionConflictException.class,
                () -> inicializacionService.inicializar(legacyEventId, request));

        assertTrue(conflict.getMessage().contains("anterior o no verificable"));
        assertEquals("a".repeat(64), eventosRepository.findByEventoId(legacyEventId)
                .orElseThrow().getHuellaInicializacion());
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sectores_disponibilidad WHERE evento_id = ?", Long.class, legacyEventId));
    }

    @Test
    void mysqlFailureAfterParentAndSectorWritesRollsBackWholeInitialization() {
        String eventoId = "evento-rollback-" + UUID.randomUUID();
        jdbcTemplate.execute("ALTER TABLE asientos_disponibilidad "
                + "ADD CONSTRAINT ck_test_init_reject_event CHECK (evento_id <> '" + eventoId + "')");
        try {
            assertThrows(DataAccessException.class,
                    () -> inicializacionService.inicializar(eventoId, solicitudMixta(null)));
            assertEquals(0L, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM eventos_disponibilidad WHERE evento_id = ?", Long.class, eventoId));
            assertEquals(0L, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sectores_disponibilidad WHERE evento_id = ?", Long.class, eventoId));
            assertEquals(0L, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM asientos_disponibilidad WHERE evento_id = ?", Long.class, eventoId));
        } finally {
            jdbcTemplate.execute("ALTER TABLE asientos_disponibilidad DROP CHECK ck_test_init_reject_event");
        }
    }

    @Test
    void concurrentIdenticalMySqlInitializationsCreateOneCompleteInventory() throws Exception {
        String eventoId = "evento-init-concurrente-" + UUID.randomUUID();
        InicializarDisponibilidadRequest request = solicitudMixta(null);
        List<EventoDisponibilidad> results = ejecutarConcurrentementeConResultados(
                () -> inicializacionService.inicializar(eventoId, request),
                () -> inicializacionService.inicializar(eventoId, request));

        assertEquals(results.get(0).getId(), results.get(1).getId());
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM eventos_disponibilidad WHERE evento_id = ?", Long.class, eventoId));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sectores_disponibilidad WHERE evento_id = ?", Long.class, eventoId));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM asientos_disponibilidad WHERE evento_id = ?", Long.class, eventoId));
    }

    @Test
    void concurrentDifferentMySqlInitializationsKeepOnlyWinnerAndReturnConflict() throws Exception {
        String eventoId = "evento-init-conflicto-" + UUID.randomUUID();
        InicializarDisponibilidadRequest first = solicitudMixta(4);
        InicializarDisponibilidadRequest second = solicitudAlternativa(4);
        List<Boolean> results = ejecutarConcurrentemente(
                () -> inicializacionService.inicializar(eventoId, first),
                () -> inicializacionService.inicializar(eventoId, second));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM eventos_disponibilidad WHERE evento_id = ?", Long.class, eventoId));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sectores_disponibilidad WHERE evento_id = ?", Long.class, eventoId));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM asientos_disponibilidad WHERE evento_id = ?", Long.class, eventoId));
    }

    @Test
    void mysqlClientEventUniqueAndCheckConstraintsArePresentAndEnforced() {
        assertEquals(1, constraintCount("cantidades_cliente_evento",
                "uk_cantidad_cliente_evento_usuario_evento", "UNIQUE"));
        assertEquals(1, constraintCount("cantidades_cliente_evento",
                "ck_cantidad_cliente_evento_no_negativa", "CHECK"));

        assertClientEventInsertRejected("usuario-neg-res-" + UUID.randomUUID(), -1, 0);
        assertClientEventInsertRejected("usuario-neg-conf-" + UUID.randomUUID(), 0, -1);

        String usuarioId = "usuario-unico-" + UUID.randomUUID();
        String eventoId = "evento-unico-" + UUID.randomUUID();
        insertarCantidad(usuarioId, eventoId, 0, 0);
        assertThrows(DataAccessException.class, () -> insertarCantidad(usuarioId, eventoId, 0, 0));
    }

    @Test
    void concurrentFirstClientEventAggregateCreationsProduceOneMySqlRow() throws Exception {
        String usuarioId = "usuario-" + UUID.randomUUID();
        String eventoId = "evento-" + UUID.randomUUID();
        guardarEvento(eventoId);
        AtomicReference<Long> firstId = new AtomicReference<>();
        AtomicReference<Long> secondId = new AtomicReference<>();

        List<Boolean> results = ejecutarConcurrentemente(
                () -> firstId.set(cantidadesService.obtenerOCrear(usuarioId, eventoId).getId()),
                () -> secondId.set(cantidadesService.obtenerOCrear(usuarioId, eventoId).getId()));

        assertEquals(2, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(firstId.get(), secondId.get());
        assertEquals(1, cantidadesRepository.countByUsuarioIdAndEventoId(usuarioId, eventoId));
    }

    @Test
    void existingMySqlAggregateIsRefreshedAfterPessimisticLock() {
        String usuarioId = "usuario-refresh-" + UUID.randomUUID();
        String eventoId = "evento-refresh-" + UUID.randomUUID();
        CantidadClienteEvento agregado = new CantidadClienteEvento(usuarioId, eventoId);
        agregado.setCantidadReservada(2);
        agregado.setCantidadConfirmada(3);
        agregado = cantidadesRepository.saveAndFlush(agregado);
        Long agregadoId = agregado.getId();

        TransactionTemplate transaccionExterna = new TransactionTemplate(transactionManager);
        transaccionExterna.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaccionExterna.execute(status -> {
            CantidadClienteEvento administrado = entityManager.find(CantidadClienteEvento.class, agregadoId);

            TransactionTemplate actualizacionConcurrente = new TransactionTemplate(transactionManager);
            actualizacionConcurrente.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            actualizacionConcurrente.execute(innerStatus -> jdbcTemplate.update(
                    "UPDATE cantidades_cliente_evento SET cantidad_reservada = 8, cantidad_confirmada = 9 "
                            + "WHERE usuario_id = ? AND evento_id = ?",
                    usuarioId, eventoId));

            assertEquals(2, administrado.getCantidadReservada());
            assertEquals(3, administrado.getCantidadConfirmada());

            CantidadClienteEvento actualizada = cantidadesService.obtenerOCrear(usuarioId, eventoId);

            assertNotSame(administrado, actualizada);
            assertEquals(8, actualizada.getCantidadReservada());
            assertEquals(9, actualizada.getCantidadConfirmada());
            return null;
        });
    }

    @Test
    void concurrentTransactionsCannotReserveTheSameMySqlSeat() throws Exception {
        String eventoId = "evento-" + UUID.randomUUID();
        String sectorId = "sector-" + UUID.randomUUID();
        guardarSector(eventoId, sectorId, TipoSector.NUMERADO, 1);
        guardarAsiento(eventoId, sectorId, 1);

        List<Boolean> results = ejecutarConcurrentemente(
                () -> disponibilidadService.reservar(List.of(detalleNumerado(eventoId, sectorId, 1))),
                () -> disponibilidadService.reservar(List.of(detalleNumerado(eventoId, sectorId, 1))));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, asientoRepository.countByEventoIdAndSectorIdAndEstado(
                eventoId, sectorId, ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoAsiento.RESERVADO));
    }

    @Test
    void concurrentTransactionsCannotOversellLastMySqlCapacity() throws Exception {
        String eventoId = "evento-" + UUID.randomUUID();
        String sectorId = "sector-" + UUID.randomUUID();
        guardarSector(eventoId, sectorId, TipoSector.NO_NUMERADO, 1);

        List<Boolean> results = ejecutarConcurrentemente(
                () -> disponibilidadService.reservar(List.of(detalleNoNumerado(eventoId, sectorId, 1))),
                () -> disponibilidadService.reservar(List.of(detalleNoNumerado(eventoId, sectorId, 1))));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        SectorDisponibilidad sector = sectorRepository.findByEventoIdAndSectorId(eventoId, sectorId).orElseThrow();
        assertEquals(1, sector.getCantidadReservada());
        assertEquals(0, sector.getCantidadVendida());
    }

    @Test
    void mysqlReservationRetriesAfterAReproducibleDeadlockWithoutDuplicateEffects() throws Exception {
        clearInvocations(reservaRepository);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String eventoId = "evento-deadlock-retry-" + suffix;
        String sectorId = "sector-deadlock-retry";
        String clienteId = "cliente-deadlock-retry-" + suffix;
        String solicitudId = "sol-deadlock-retry-" + suffix;
        String scratchTable = "deadlock_weight_" + suffix;

        inicializacionService.inicializar(eventoId, new InicializarDisponibilidadRequest(4, List.of(
                new SectorInicializacionRequest(sectorId, TipoSector.NO_NUMERADO, 4, List.of()))));
        estadoVentaService.aplicar(eventoId, EstadoVenta.ACTIVA);
        cantidadesService.obtenerOCrear(clienteId, eventoId);
        jdbcTemplate.execute("CREATE TABLE " + scratchTable
                + " (id INT PRIMARY KEY, value INT NOT NULL) ENGINE=InnoDB");
        jdbcTemplate.batchUpdate("INSERT INTO " + scratchTable + " (id, value) VALUES (?, 0)",
                java.util.stream.IntStream.rangeClosed(1, 100).mapToObj(id -> new Object[] { id }).toList());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ResultadoCreacionReserva> creation = null;
        try (Connection blocker = DriverManager.getConnection(
                temporaryDatabase.jdbcUrl, temporaryDatabase.username, temporaryDatabase.password)) {
            blocker.setAutoCommit(false);
            try {
                try (var lockAggregate = blocker.prepareStatement(
                        "SELECT id FROM cantidades_cliente_evento WHERE usuario_id=? AND evento_id=? FOR UPDATE")) {
                    lockAggregate.setString(1, clienteId);
                    lockAggregate.setString(2, eventoId);
                    try (var rows = lockAggregate.executeQuery()) {
                        assertTrue(rows.next(), "El agregado cliente/evento de contención debe existir");
                    }
                }
                try (var updateUndo = blocker.createStatement()) {
                    updateUndo.executeUpdate("UPDATE " + scratchTable + " SET value = value + 1");
                }

                creation = executor.submit(() -> reservaService.crear(eventoId, clienteId,
                        requestCantidad(solicitudId, sectorId, 1)));
                assertTrue(eventWaitsForCustomerAggregate(),
                        "La solicitud debe retener el lock del evento y esperar el agregado del cliente");
                assertTrue(!creation.isDone(), "La solicitud debe seguir bloqueada antes de cerrar el ciclo");

                // La transacción blocker ya acumula más undo que la reserva. Al pedir el lock
                // del evento crea un ciclo y MySQL debe seleccionar la transacción de reserva.
                // Que este SELECT obtenga la fila bloqueada demuestra que la otra transacción
                // fue elegida víctima por el deadlock; su servicio debe reintentar después.
                try (var closeCycle = blocker.prepareStatement(
                        "SELECT id FROM eventos_disponibilidad WHERE evento_id=? FOR UPDATE")) {
                    closeCycle.setString(1, eventoId);
                    try (var rows = closeCycle.executeQuery()) {
                        assertTrue(rows.next());
                    }
                }
                blocker.commit();

                ResultadoCreacionReserva result = creation.get(30, TimeUnit.SECONDS);
                assertEquals(201, result.status().value(),
                        "La creación debe completar el reintento después del deadlock");
                verify(reservaRepository, atLeast(2)).findByUsuarioIdAndClaveIdempotencia(
                        clienteId, solicitudId);
                assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM reservas WHERE usuario_id=? AND clave_idempotencia=?",
                        Integer.class, clienteId, solicitudId));
                assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM detalles_reserva d JOIN reservas r ON r.id=d.reserva_id "
                                + "WHERE r.usuario_id=? AND r.clave_idempotencia=?",
                        Integer.class, clienteId, solicitudId));
                assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT cantidad_reservada FROM sectores_disponibilidad WHERE evento_id=? AND sector_id=?",
                        Integer.class, eventoId, sectorId));
                assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT cantidad_reservada FROM cantidades_cliente_evento WHERE usuario_id=? AND evento_id=?",
                        Integer.class, clienteId, eventoId));
            } finally {
                try {
                    blocker.rollback();
                } catch (SQLException ignored) {
                    // The blocker may already have committed after closing the cycle.
                }
            }
        } finally {
            if (creation != null && !creation.isDone()) {
                creation.cancel(true);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            jdbcTemplate.execute("DROP TABLE " + scratchTable);
        }
    }

    @Test
    void concurrentMySqlReservationRequestsCannotReserveTheSameSeatTwice() throws Exception {
        String eventoId = "evento-reserva-concurrente-" + UUID.randomUUID();
        String sectorId = "sector-reserva-concurrente";
        inicializacionService.inicializar(eventoId, new InicializarDisponibilidadRequest(4, List.of(
                new SectorInicializacionRequest(sectorId, TipoSector.NUMERADO, 1,
                        List.of(new AsientoInicializacionRequest(1, 1))))));
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id = ?", eventoId);

        List<Boolean> results = ejecutarConcurrentemente(
                () -> reservaService.crear(eventoId, "cliente-a", request("reserva-a", sectorId, 1, 1)),
                () -> reservaService.crear(eventoId, "cliente-b", request("reserva-b", sectorId, 1, 1)));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservas WHERE evento_id = ?", Integer.class,
                eventoId));
        assertEquals("RESERVADO", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id = ? AND sector_id = ? AND fila = 1 AND numero = 1",
                String.class, eventoId, sectorId));
    }

    @Test
    void concurrentMySqlReservationRequestsCannotOversellNonNumberedCapacity() throws Exception {
        String eventoId = "evento-capacidad-reserva-" + UUID.randomUUID();
        String sectorId = "sector-general-reserva";
        inicializacionService.inicializar(eventoId, new InicializarDisponibilidadRequest(4, List.of(
                new SectorInicializacionRequest(sectorId, TipoSector.NO_NUMERADO, 1, List.of()))));
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id = ?", eventoId);

        List<Boolean> results = ejecutarConcurrentemente(
                () -> reservaService.crear(eventoId, "cliente-a", requestCantidad("reserva-a", sectorId, 1)),
                () -> reservaService.crear(eventoId, "cliente-b", requestCantidad("reserva-b", sectorId, 1)));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT cantidad_reservada FROM sectores_disponibilidad "
                + "WHERE evento_id = ? AND sector_id = ?", Integer.class, eventoId, sectorId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservas WHERE evento_id = ?", Integer.class,
                eventoId));
    }

    @Test
    void concurrentMySqlIdenticalReservationRetriesCreateOneHoldAndReturnSameReservation() throws Exception {
        String eventoId = "evento-reintento-reserva-" + UUID.randomUUID();
        String sectorId = "sector-reintento-reserva";
        inicializacionService.inicializar(eventoId, new InicializarDisponibilidadRequest(4, List.of(
                new SectorInicializacionRequest(sectorId, TipoSector.NUMERADO, 1,
                        List.of(new AsientoInicializacionRequest(1, 1))))));
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id = ?", eventoId);
        CrearReservaRequest same = request("reserva-idempotente", sectorId, 1, 1);

        List<ResultadoCreacionReserva> results = ejecutarConcurrentementeConResultados(
                () -> reservaService.crear(eventoId, "cliente-idempotente", same),
                () -> reservaService.crear(eventoId, "cliente-idempotente", same));

        assertEquals(List.of(200, 201), results.stream().map(result -> result.status().value()).sorted().toList());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservas WHERE evento_id = ?", Integer.class,
                eventoId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT cantidad_reservada FROM cantidades_cliente_evento "
                + "WHERE usuario_id = ? AND evento_id = ?", Integer.class, "cliente-idempotente", eventoId));
        assertEquals(results.get(0).reserva().reservaId(), results.get(1).reserva().reservaId());
    }

    @Test
    void concurrentSameClientRequestIdAcrossDifferentEventsCreatesOnlyOneReservation() throws Exception {
        String firstEventId = "evento-clave-a-" + UUID.randomUUID();
        String secondEventId = "evento-clave-b-" + UUID.randomUUID();
        String firstSectorId = "sector-clave-a";
        String secondSectorId = "sector-clave-b";
        inicializarEventoNumerado(firstEventId, firstSectorId);
        inicializarEventoNumerado(secondEventId, secondSectorId);
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id IN (?, ?)",
                firstEventId, secondEventId);

        List<Integer> statuses = ejecutarConcurrentementeConResultados(
                () -> createStatusOrConflict(firstEventId, "cliente-clave-race", "solicitud-clave-race",
                        request("solicitud-clave-race", firstSectorId, 1, 1)),
                () -> createStatusOrConflict(secondEventId, "cliente-clave-race", "solicitud-clave-race",
                        request("solicitud-clave-race", secondSectorId, 1, 1)));

        assertEquals(List.of(201, 409), statuses.stream().sorted().toList());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservas "
                + "WHERE usuario_id='cliente-clave-race' AND clave_idempotencia='solicitud-clave-race'", Integer.class));
        String winningEvent = jdbcTemplate.queryForObject("SELECT evento_id FROM reservas "
                + "WHERE usuario_id='cliente-clave-race' AND clave_idempotencia='solicitud-clave-race'", String.class);
        String losingEvent = winningEvent.equals(firstEventId) ? secondEventId : firstEventId;
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cantidades_cliente_evento "
                + "WHERE usuario_id='cliente-clave-race' AND evento_id=? AND cantidad_reservada=1", Integer.class,
                winningEvent));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cantidades_cliente_evento "
                + "WHERE usuario_id='cliente-clave-race' AND evento_id=?", Integer.class, losingEvent));
        assertEquals("RESERVADO", jdbcTemplate.queryForObject("SELECT estado FROM asientos_disponibilidad "
                + "WHERE evento_id=?", String.class, winningEvent));
        assertEquals("LIBRE", jdbcTemplate.queryForObject("SELECT estado FROM asientos_disponibilidad "
                + "WHERE evento_id=?", String.class, losingEvent));
    }

    @Test
    void concurrentRequestsCannotExceedPerClientEventLimit() throws Exception {
        String eventoId = "evento-limite-race-" + UUID.randomUUID();
        String sectorId = "sector-limite-race";
        inicializacionService.inicializar(eventoId, new InicializarDisponibilidadRequest(4, List.of(
                new SectorInicializacionRequest(sectorId, TipoSector.NO_NUMERADO, 6, List.of()))));
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id = ?", eventoId);
        assertEquals(201, reservaService.crear(eventoId, "cliente-limite-race",
                requestCantidad("solicitud-limite-inicial", sectorId, 3)).status().value());

        List<Integer> statuses = ejecutarConcurrentementeConResultados(
                () -> createStatusOrConflict(eventoId, "cliente-limite-race", "solicitud-limite-a",
                        requestCantidad("solicitud-limite-a", sectorId, 1)),
                () -> createStatusOrConflict(eventoId, "cliente-limite-race", "solicitud-limite-b",
                        requestCantidad("solicitud-limite-b", sectorId, 1)));

        assertEquals(List.of(201, 409), statuses.stream().sorted().toList());
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservas WHERE evento_id=? "
                + "AND usuario_id='cliente-limite-race'", Integer.class, eventoId));
        assertEquals(4, jdbcTemplate.queryForObject("SELECT cantidad_reservada FROM sectores_disponibilidad "
                + "WHERE evento_id=? AND sector_id=?", Integer.class, eventoId, sectorId));
        assertEquals(4, jdbcTemplate.queryForObject("SELECT cantidad_reservada FROM cantidades_cliente_evento "
                + "WHERE usuario_id='cliente-limite-race' AND evento_id=?", Integer.class, eventoId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT cantidad_confirmada FROM cantidades_cliente_evento "
                + "WHERE usuario_id='cliente-limite-race' AND evento_id=?", Integer.class, eventoId));
    }

    @Test
    void mysqlClientLimitsAreIndependentAcrossCustomersAndEvents() throws Exception {
        String firstEventId = "evento-limite-independiente-a-" + UUID.randomUUID();
        String secondEventId = "evento-limite-independiente-b-" + UUID.randomUUID();
        String sectorId = "sector-limite-independiente";
        for (String eventoId : List.of(firstEventId, secondEventId)) {
            inicializacionService.inicializar(eventoId, new InicializarDisponibilidadRequest(4, List.of(
                    new SectorInicializacionRequest(sectorId, TipoSector.NO_NUMERADO, 2, List.of()))));
            estadoVentaService.aplicar(eventoId, EstadoVenta.ACTIVA);
        }

        List<Integer> statuses = ejecutarConcurrentementeConResultados(
                () -> reservaService.crear(firstEventId, "cliente-limite-a",
                        requestCantidad("solicitud-limite-a", sectorId, 1)).status().value(),
                () -> reservaService.crear(firstEventId, "cliente-limite-b",
                        requestCantidad("solicitud-limite-b", sectorId, 1)).status().value(),
                () -> reservaService.crear(secondEventId, "cliente-limite-a",
                        requestCantidad("solicitud-limite-otro-evento", sectorId, 1)).status().value());

        assertEquals(List.of(201, 201, 201), statuses);
        assertEquals(1, jdbcTemplate.queryForObject("SELECT cantidad_reservada FROM cantidades_cliente_evento "
                + "WHERE usuario_id='cliente-limite-a' AND evento_id=?", Integer.class, firstEventId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT cantidad_reservada FROM cantidades_cliente_evento "
                + "WHERE usuario_id='cliente-limite-b' AND evento_id=?", Integer.class, firstEventId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT cantidad_reservada FROM cantidades_cliente_evento "
                + "WHERE usuario_id='cliente-limite-a' AND evento_id=?", Integer.class, secondEventId));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT cantidad_reservada FROM sectores_disponibilidad "
                + "WHERE evento_id=? AND sector_id=?", Integer.class, firstEventId, sectorId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT cantidad_reservada FROM sectores_disponibilidad "
                + "WHERE evento_id=? AND sector_id=?", Integer.class, secondEventId, sectorId));
    }

    @Test
    void mysqlInitializationPersistsEventLimitAndRejectsNonPositiveValuesBeforeWriting() {
        String eventId = "evento-limite-fijo-mysql-" + UUID.randomUUID();
        var sectors = List.of(new SectorInicializacionRequest("sector-limite-fijo", TipoSector.NO_NUMERADO,
                10, List.of()));

        for (Integer invalidLimit : List.of(-1, 0)) {
            String invalidEventId = eventId + "-" + invalidLimit;
            assertThrows(ConstraintViolationException.class, () -> inicializacionService.inicializar(
                    invalidEventId, new InicializarDisponibilidadRequest(invalidLimit, sectors)));
            assertTrue(eventosRepository.findByEventoId(invalidEventId).isEmpty());
        }
        String nullEventId = eventId + "-null";
        assertThrows(ConstraintViolationException.class, () -> inicializacionService.inicializar(nullEventId,
                new InicializarDisponibilidadRequest(null, sectors)));
        assertTrue(eventosRepository.findByEventoId(nullEventId).isEmpty());

        inicializacionService.inicializar(eventId, new InicializarDisponibilidadRequest(4, sectors));
        assertEquals(4, eventosRepository.findByEventoId(eventId).orElseThrow().getLimiteEntradasPorCliente());
    }

    @Test
    void reservationExpiryRoundTripsAsTheSameUtcInstantInMySql() {
        String eventoId = "evento-expira-utc-" + UUID.randomUUID();
        String sectorId = "sector-expira-utc";
        inicializarEventoNumerado(eventoId, sectorId);
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id = ?", eventoId);

        var response = reservaService.crear(eventoId, "cliente-expira-utc",
                request("solicitud-expira-utc", sectorId, 1, 1)).reserva();
        entityManager.clear();
        Reserva recovered = entityManager.find(Reserva.class, response.reservaId());

        assertEquals(response.expiraEn(), recovered.getExpiraEn().toInstant(java.time.ZoneOffset.UTC));
    }

    @Test
    void concurrentMySqlConfirmationsOfOneReservationTransferCountsOnlyOnce() throws Exception {
        String eventoId = "evento-confirmacion-concurrente-" + UUID.randomUUID();
        String sectorId = "sector-confirmacion-concurrente";
        inicializarEventoNumerado(eventoId, sectorId);
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id = ?", eventoId);
        var reserva = reservaService.crear(eventoId, "cliente-confirmacion-concurrente",
                request("solicitud-confirmacion-concurrente", sectorId, 1, 1)).reserva();

        List<Boolean> results = ejecutarConcurrentemente(
                () -> reservaService.confirmar(reserva.reservaId()),
                () -> reservaService.confirmar(reserva.reservaId()));

        assertEquals(List.of(true, true), results);
        assertEquals("CONFIRMADA", jdbcTemplate.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, reserva.reservaId()));
        assertEquals("VENDIDO", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id=? AND sector_id=? AND fila=1 AND numero=1",
                String.class, eventoId, sectorId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM cantidades_cliente_evento WHERE usuario_id=? AND evento_id=?",
                Integer.class, "cliente-confirmacion-concurrente", eventoId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT cantidad_confirmada FROM cantidades_cliente_evento WHERE usuario_id=? AND evento_id=?",
                Integer.class, "cliente-confirmacion-concurrente", eventoId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notificaciones_disponibilidad_outbox WHERE evento_id=? AND agotado=1",
                Integer.class, eventoId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notificaciones_disponibilidad_outbox WHERE evento_id=?",
                Integer.class, eventoId));
    }

    @Test
    void mysqlPartialRefundWritesRestorationNotificationOnceAndKeepsSuspension() {
        String eventoId = "evento-outbox-refund-" + UUID.randomUUID();
        String sectorId = "sector-outbox-refund";
        inicializarEventoNumerado(eventoId, sectorId);
        estadoVentaService.aplicar(eventoId, EstadoVenta.ACTIVA);
        String clienteId = "cliente-outbox-refund";
        var reserva = reservaService.crear(eventoId, clienteId,
                request("sol-outbox-refund-" + UUID.randomUUID(), sectorId, 1, 1)).reserva();
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notificaciones_disponibilidad_outbox WHERE evento_id=?",
                Integer.class, eventoId));

        reservaService.confirmar(reserva.reservaId());
        estadoVentaService.aplicar(eventoId, EstadoVenta.SUSPENDIDA);
        String devolucionId = UUID.randomUUID().toString();
        var request = new LiberarReservaRequest("DEVOLUCION_APROBADA", devolucionId,
                List.of(new ar.edu.unicen.ms_disponibilidad_reservas.dto.DetalleDevolucionRequest(
                        sectorId, 1, 1, null)));
        devolucionService.devolverAprobada(reserva.reservaId(), request);
        devolucionService.devolverAprobada(reserva.reservaId(), request);

        assertEquals(List.of(1, 0), jdbcTemplate.queryForList(
                "SELECT agotado FROM notificaciones_disponibilidad_outbox WHERE evento_id=? ORDER BY id",
                Integer.class, eventoId));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notificaciones_disponibilidad_outbox WHERE evento_id=?",
                Integer.class, eventoId));
        assertEquals("PUBLICADO", jdbcTemplate.queryForObject(
                "SELECT estado_evento FROM eventos_disponibilidad WHERE evento_id=?", String.class, eventoId));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT venta_suspendida FROM eventos_disponibilidad WHERE evento_id=?", Boolean.class, eventoId));
    }

    @Test
    void concurrentMySqlPaymentRejectionReleasesOneReservationOnlyOnce() throws Exception {
        String eventoId = "evento-liberacion-concurrente-" + UUID.randomUUID();
        String sectorId = "sector-liberacion-concurrente";
        inicializarEventoNumerado(eventoId, sectorId);
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id = ?", eventoId);
        var reserva = reservaService.crear(eventoId, "cliente-liberacion-concurrente",
                request("solicitud-liberacion-concurrente", sectorId, 1, 1)).reserva();

        List<Boolean> results = ejecutarConcurrentemente(
                () -> reservaService.liberarPorPagoRechazado(reserva.reservaId()),
                () -> reservaService.liberarPorPagoRechazado(reserva.reservaId()));

        assertEquals(List.of(true, true), results);
        assertEquals("LIBERADA", jdbcTemplate.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, reserva.reservaId()));
        assertEquals("LIBRE", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id=? AND sector_id=? AND fila=1 AND numero=1",
                String.class, eventoId, sectorId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM cantidades_cliente_evento WHERE usuario_id=? AND evento_id=?",
                Integer.class, "cliente-liberacion-concurrente", eventoId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_confirmada FROM cantidades_cliente_evento WHERE usuario_id=? AND evento_id=?",
                Integer.class, "cliente-liberacion-concurrente", eventoId));
    }

    @Test
    void concurrentMySqlRetriesOfOneRefundIdApplyRefundExactlyOnce() throws Exception {
        String eventoId = "evento-devolucion-idempotente-" + UUID.randomUUID();
        String sectorId = "sector-devolucion-idempotente";
        inicializarEventoNoNumerado(eventoId, sectorId, 10);
        estadoVentaService.aplicar(eventoId, EstadoVenta.ACTIVA);
        var reserva = reservaService.crear(eventoId, "cliente-devolucion-idempotente",
                requestCantidad("solicitud-devolucion-idempotente", sectorId, 4)).reserva();
        reservaService.confirmar(reserva.reservaId());
        String devolucionId = UUID.randomUUID().toString();
        LiberarReservaRequest request = devolucion(devolucionId, sectorId, 2);

        List<Boolean> results = ejecutarConcurrentemente(
                () -> devolucionService.devolverAprobada(reserva.reservaId(), request),
                () -> devolucionService.devolverAprobada(reserva.reservaId(), request));

        assertEquals(List.of(true, true), results);
        assertEquals(2, jdbcTemplate.queryForObject("SELECT cantidad_vendida FROM sectores_disponibilidad "
                + "WHERE evento_id=? AND sector_id=?", Integer.class, eventoId, sectorId));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT cantidad_confirmada FROM cantidades_cliente_evento "
                + "WHERE usuario_id=? AND evento_id=?", Integer.class, "cliente-devolucion-idempotente", eventoId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM devoluciones WHERE id=?",
                Integer.class, devolucionId));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT SUM(d.cantidad) FROM devolucion_detalles_cantidades d "
                + "JOIN devoluciones v ON v.id=d.devolucion_id WHERE v.reserva_id=?", Integer.class,
                reserva.reservaId()));
    }

    @Test
    void concurrentMySqlRefundIdsCannotReturnMoreThanRemainingQuantity() throws Exception {
        String eventoId = "evento-devolucion-cantidad-race-" + UUID.randomUUID();
        String sectorId = "sector-devolucion-cantidad-race";
        inicializarEventoNoNumerado(eventoId, sectorId, 10);
        estadoVentaService.aplicar(eventoId, EstadoVenta.ACTIVA);
        String clienteId = "cliente-devolucion-cantidad-race";
        var reserva = reservaService.crear(eventoId, clienteId,
                requestCantidad("solicitud-devolucion-cantidad-race", sectorId, 4)).reserva();
        reservaService.confirmar(reserva.reservaId());

        List<Boolean> results = ejecutarConcurrentemente(
                () -> devolucionService.devolverAprobada(reserva.reservaId(),
                        devolucion(UUID.randomUUID().toString(), sectorId, 3)),
                () -> devolucionService.devolverAprobada(reserva.reservaId(),
                        devolucion(UUID.randomUUID().toString(), sectorId, 3)));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT cantidad_vendida FROM sectores_disponibilidad "
                + "WHERE evento_id=? AND sector_id=?", Integer.class, eventoId, sectorId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT cantidad_confirmada FROM cantidades_cliente_evento "
                + "WHERE usuario_id=? AND evento_id=?", Integer.class, clienteId, eventoId));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT SUM(d.cantidad) FROM devolucion_detalles_cantidades d "
                + "JOIN devoluciones v ON v.id=d.devolucion_id WHERE v.reserva_id=?", Integer.class,
                reserva.reservaId()));
    }

    @Test
    void concurrentMySqlExpirationConfirmationAndManualReleaseApplyOneTerminalTransition() throws Exception {
        String eventoId = "evento-expiracion-concurrente-" + UUID.randomUUID();
        String sectorId = "sector-expiracion-concurrente";
        inicializarEventoNumerado(eventoId, sectorId);
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id = ?", eventoId);
        String clienteId = "cliente-expiracion-concurrente";
        var reserva = reservaService.crear(eventoId, clienteId,
                request("solicitud-expiracion-concurrente", sectorId, 1, 1)).reserva();
        jdbcTemplate.update("UPDATE reservas SET expira_en = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?",
                reserva.reservaId());

        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<Integer> expiration = executor.submit(() -> {
                awaitConcurrentStart(ready, start);
                return reservaService.expirarReservasVencidas();
            });
            Future<Boolean> confirmation = executor.submit(() -> {
                awaitConcurrentStart(ready, start);
                try {
                    reservaService.confirmar(reserva.reservaId());
                } catch (ReservaVencidaException | ReservaLiberadaException expected) {
                    return true;
                }
                return true;
            });
            Future<Boolean> manualRelease = executor.submit(() -> {
                awaitConcurrentStart(ready, start);
                reservaService.liberarPorPagoRechazado(reserva.reservaId());
                return true;
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(expiration.get(30, TimeUnit.SECONDS) >= 0);
            assertTrue(confirmation.get(30, TimeUnit.SECONDS));
            assertTrue(manualRelease.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals("LIBERADA", jdbcTemplate.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, reserva.reservaId()));
        assertEquals("LIBRE", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id=? AND sector_id=? AND fila=1 AND numero=1",
                String.class, eventoId, sectorId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM cantidades_cliente_evento WHERE usuario_id=? AND evento_id=?",
                Integer.class, clienteId, eventoId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_confirmada FROM cantidades_cliente_evento WHERE usuario_id=? AND evento_id=?",
                Integer.class, clienteId, eventoId));
    }

    @Test
    void concurrentMySqlSalesStateChangesAndReservationCreationSerializeOnEventLock() throws Exception {
        String activationEvent = "evento-activar-race-" + UUID.randomUUID();
        String activationSector = "sector-activar-race";
        inicializarEventoNumerado(activationEvent, activationSector);

        List<Integer> activationResults = ejecutarConcurrentementeConResultados(
                () -> {
                    estadoVentaService.aplicar(activationEvent, EstadoVenta.ACTIVA);
                    return 204;
                },
                () -> createStatusOrConflict(activationEvent, "cliente-activar-race", "sol-activar-race",
                        request("sol-activar-race", activationSector, 1, 1)));

        assertEquals(204, activationResults.stream().filter(status -> status == 204).findFirst().orElseThrow());
        assertEquals("PUBLICADO", jdbcTemplate.queryForObject(
                "SELECT estado_evento FROM eventos_disponibilidad WHERE evento_id=?", String.class, activationEvent));
        int activationReservationStatus = activationResults.stream().filter(status -> status != 204)
                .findFirst().orElseThrow();
        assertTrue(activationReservationStatus == 201 || activationReservationStatus == 409);
        assertEquals(activationReservationStatus == 201 ? 1 : 0,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservas WHERE evento_id=?", Integer.class,
                        activationEvent));
        assertEquals(activationReservationStatus == 201 ? "RESERVADO" : "LIBRE",
                jdbcTemplate.queryForObject("SELECT estado FROM asientos_disponibilidad WHERE evento_id=?",
                        String.class, activationEvent));

        String suspensionEvent = "evento-suspender-race-" + UUID.randomUUID();
        String suspensionSector = "sector-suspender-race";
        inicializarEventoNumerado(suspensionEvent, suspensionSector);
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id=?",
                suspensionEvent);

        List<Integer> suspensionResults = ejecutarConcurrentementeConResultados(
                () -> {
                    estadoVentaService.aplicar(suspensionEvent, EstadoVenta.SUSPENDIDA);
                    return 204;
                },
                () -> createStatusOrConflict(suspensionEvent, "cliente-suspender-race", "sol-suspender-race",
                        request("sol-suspender-race", suspensionSector, 1, 1)));

        assertEquals(204, suspensionResults.stream().filter(status -> status == 204).findFirst().orElseThrow());
        assertEquals("PUBLICADO", jdbcTemplate.queryForObject(
                "SELECT estado_evento FROM eventos_disponibilidad WHERE evento_id=?", String.class, suspensionEvent));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT venta_suspendida FROM eventos_disponibilidad WHERE evento_id=?", Integer.class,
                suspensionEvent));
        int suspensionReservationStatus = suspensionResults.stream().filter(status -> status != 204)
                .findFirst().orElseThrow();
        assertTrue(suspensionReservationStatus == 201 || suspensionReservationStatus == 409);
        assertEquals(suspensionReservationStatus == 201 ? 1 : 0,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reservas WHERE evento_id=?", Integer.class,
                        suspensionEvent));
        assertEquals(suspensionReservationStatus == 201 ? "RESERVADO" : "LIBRE",
                jdbcTemplate.queryForObject("SELECT estado FROM asientos_disponibilidad WHERE evento_id=?",
                        String.class, suspensionEvent));
    }

    @Test
    void concurrentCancellationConfirmationManualReleaseAndExpirationLeaveNoPartialReservationState() throws Exception {
        concurrentTerminalTransitionWithReservationLifecycle(EstadoVenta.CANCELADA);
        concurrentTerminalTransitionWithReservationLifecycle(EstadoVenta.FINALIZADA);
    }

    private void concurrentTerminalTransitionWithReservationLifecycle(EstadoVenta estadoTerminal) throws Exception {
        String suffix = UUID.randomUUID().toString();
        String eventoId = "evento-terminal-race-" + suffix;
        String numberedSector = "sector-terminal-race-num";
        String generalSector = "sector-terminal-race-general";
        inicializacionService.inicializar(eventoId, new InicializarDisponibilidadRequest(4, List.of(
                new SectorInicializacionRequest(numberedSector, TipoSector.NUMERADO, 3, List.of(
                        new AsientoInicializacionRequest(1, 1),
                        new AsientoInicializacionRequest(1, 2),
                        new AsientoInicializacionRequest(1, 3))),
                new SectorInicializacionRequest(generalSector, TipoSector.NO_NUMERADO, 9, List.of()))));
        estadoVentaService.aplicar(eventoId, EstadoVenta.ACTIVA);

        String confirmClient = "cliente-terminal-confirm-" + suffix;
        String releaseClient = "cliente-terminal-release-" + suffix;
        String expireClient = "cliente-terminal-expire-" + suffix;
        var confirmReservation = reservaService.crear(eventoId, confirmClient,
                mixedRequest("sol-terminal-confirm-" + suffix, numberedSector, 1, generalSector, 2)).reserva();
        var manualReleaseReservation = reservaService.crear(eventoId, releaseClient,
                mixedRequest("sol-terminal-release-" + suffix, numberedSector, 2, generalSector, 2)).reserva();
        var expiringReservation = reservaService.crear(eventoId, expireClient,
                mixedRequest("sol-terminal-expire-" + suffix, numberedSector, 3, generalSector, 2)).reserva();
        jdbcTemplate.update("UPDATE reservas SET expira_en=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
                expiringReservation.reservaId());

        ejecutarConcurrentementeEnGrupo(List.of(
                () -> estadoVentaService.aplicar(eventoId, estadoTerminal),
                () -> confirmarIgnorandoConflicto(confirmReservation.reservaId()),
                () -> reservaService.liberarPorPagoRechazado(manualReleaseReservation.reservaId()),
                reservaService::expirarReservasVencidas));

        String expectedEventState = estadoTerminal == EstadoVenta.CANCELADA ? "CANCELADO" : "FINALIZADO";
        assertEquals(expectedEventState, jdbcTemplate.queryForObject(
                "SELECT estado_evento FROM eventos_disponibilidad WHERE evento_id=?", String.class, eventoId));
        String confirmState = jdbcTemplate.queryForObject("SELECT estado FROM reservas WHERE id=?",
                String.class, confirmReservation.reservaId());
        assertTrue(confirmState.equals("CONFIRMADA") || confirmState.equals("LIBERADA"));
        assertEquals("LIBERADA", jdbcTemplate.queryForObject("SELECT estado FROM reservas WHERE id=?",
                String.class, manualReleaseReservation.reservaId()));
        assertEquals("LIBERADA", jdbcTemplate.queryForObject("SELECT estado FROM reservas WHERE id=?",
                String.class, expiringReservation.reservaId()));

        int confirmed = confirmState.equals("CONFIRMADA") ? 1 : 0;
        assertEquals(0, jdbcTemplate.queryForObject("SELECT cantidad_reservada FROM sectores_disponibilidad "
                + "WHERE evento_id=? AND sector_id=?", Integer.class, eventoId, generalSector));
        assertEquals(confirmed * 2, jdbcTemplate.queryForObject("SELECT cantidad_vendida FROM sectores_disponibilidad "
                + "WHERE evento_id=? AND sector_id=?", Integer.class, eventoId, generalSector));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asientos_disponibilidad "
                + "WHERE evento_id=? AND estado='RESERVADO'", Integer.class, eventoId));
        assertEquals(confirmed, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asientos_disponibilidad "
                + "WHERE evento_id=? AND estado='VENDIDO'", Integer.class, eventoId));
        for (String clienteId : List.of(confirmClient, releaseClient, expireClient)) {
            assertEquals(0, jdbcTemplate.queryForObject("SELECT cantidad_reservada FROM cantidades_cliente_evento "
                    + "WHERE usuario_id=? AND evento_id=?", Integer.class, clienteId, eventoId));
        }
        assertEquals(confirmed * 3, jdbcTemplate.queryForObject("SELECT SUM(cantidad_confirmada) "
                + "FROM cantidades_cliente_evento WHERE evento_id=?", Integer.class, eventoId));
    }

    private CrearReservaRequest mixedRequest(String solicitudId, String numberedSector, int numero,
            String generalSector, int cantidad) {
        return new CrearReservaRequest(solicitudId, List.of(
                new DetalleReservaSolicitud(numberedSector, 1, numero, null),
                new DetalleReservaSolicitud(generalSector, null, null, cantidad)));
    }

    private void confirmarIgnorandoConflicto(String reservaId) {
        try {
            reservaService.confirmar(reservaId);
        } catch (ReservaVencidaException | ReservaLiberadaException | ReservaConflictException expected) {
            // Cancellation/release/expiration won the reservation row lock.
        }
    }

    private void ejecutarConcurrentementeEnGrupo(List<Runnable> operaciones) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(operaciones.size());
        CountDownLatch preparadas = new CountDownLatch(operaciones.size());
        CountDownLatch inicio = new CountDownLatch(1);
        try {
            List<Future<Object>> resultados = operaciones.stream().map(operacion -> executor.submit(() -> {
                preparadas.countDown();
                assertTrue(inicio.await(10, TimeUnit.SECONDS));
                operacion.run();
                return null;
            })).toList();
            assertTrue(preparadas.await(10, TimeUnit.SECONDS));
            inicio.countDown();
            for (Future<?> resultado : resultados) {
                resultado.get(30, TimeUnit.SECONDS);
            }
        } finally {
            inicio.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void awaitConcurrentStart(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
    }

    private void inicializarEventoNumerado(String eventoId, String sectorId) {
        inicializacionService.inicializar(eventoId, new InicializarDisponibilidadRequest(4, List.of(
                new SectorInicializacionRequest(sectorId, TipoSector.NUMERADO, 1,
                        List.of(new AsientoInicializacionRequest(1, 1))))));
    }

    private void inicializarEventoNoNumerado(String eventoId, String sectorId, int capacidad) {
        inicializacionService.inicializar(eventoId, new InicializarDisponibilidadRequest(4, List.of(
                new SectorInicializacionRequest(sectorId, TipoSector.NO_NUMERADO, capacidad, List.of()))));
    }

    private LiberarReservaRequest devolucion(String devolucionId, String sectorId, int cantidad) {
        return new LiberarReservaRequest("DEVOLUCION_APROBADA", devolucionId,
                List.of(new ar.edu.unicen.ms_disponibilidad_reservas.dto.DetalleDevolucionRequest(
                        sectorId, null, null, cantidad)));
    }

    private int createStatusOrConflict(String eventoId, String clienteId, String solicitudId,
            CrearReservaRequest request) {
        try {
            return reservaService.crear(eventoId, clienteId, request).status().value();
        } catch (ReservaConflictException conflict) {
            return 409;
        }
    }

    @Test
    void failedMultiSeatOperationRollsBackEarlierMySqlUpdates() {
        String eventoId = "evento-" + UUID.randomUUID();
        String sectorId = "sector-" + UUID.randomUUID();
        guardarSector(eventoId, sectorId, TipoSector.NUMERADO, 2);
        guardarAsiento(eventoId, sectorId, 1);
        guardarAsiento(eventoId, sectorId, 2);
        DetalleReserva primero = detalleNumerado(eventoId, sectorId, 1);
        DetalleReserva segundo = detalleNumerado(eventoId, sectorId, 2);

        disponibilidadService.reservar(List.of(segundo));
        org.junit.jupiter.api.Assertions.assertThrows(DisponibilidadException.class,
                () -> disponibilidadService.reservar(List.of(primero, segundo)));

        assertEquals("LIBRE", estado(eventoId, sectorId, 1));
        assertEquals("RESERVADO", estado(eventoId, sectorId, 2));
    }

    private List<Boolean> ejecutarConcurrentemente(Runnable primera, Runnable segunda) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch preparadas = new CountDownLatch(2);
        CountDownLatch inicio = new CountDownLatch(1);
        try {
            Future<Boolean> resultado1 = executor.submit(() -> ejecutarTrasBarrera(primera, preparadas, inicio));
            Future<Boolean> resultado2 = executor.submit(() -> ejecutarTrasBarrera(segunda, preparadas, inicio));
            assertTrue(preparadas.await(10, TimeUnit.SECONDS));
            inicio.countDown();
            return List.of(resultado1.get(30, TimeUnit.SECONDS), resultado2.get(30, TimeUnit.SECONDS));
        } finally {
            inicio.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private boolean ejecutarTrasBarrera(Runnable operacion, CountDownLatch preparadas, CountDownLatch inicio)
            throws Exception {
        preparadas.countDown();
        assertTrue(inicio.await(10, TimeUnit.SECONDS));
        try {
            operacion.run();
            return true;
        } catch (DisponibilidadException | InicializacionConflictException | ReservaConflictException conflictoEsperado) {
            return false;
        }
    }

    private <T> List<T> ejecutarConcurrentementeConResultados(Callable<T> primera, Callable<T> segunda)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch preparadas = new CountDownLatch(2);
        CountDownLatch inicio = new CountDownLatch(1);
        try {
            Future<T> resultado1 = executor.submit(
                    () -> ejecutarTrasBarrera(primera, preparadas, inicio));
            Future<T> resultado2 = executor.submit(
                    () -> ejecutarTrasBarrera(segunda, preparadas, inicio));
            assertTrue(preparadas.await(10, TimeUnit.SECONDS));
            inicio.countDown();
            return List.of(resultado1.get(30, TimeUnit.SECONDS), resultado2.get(30, TimeUnit.SECONDS));
        } finally {
            inicio.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private <T> List<T> ejecutarConcurrentementeConResultados(
            Callable<T> primera, Callable<T> segunda, Callable<T> tercera) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch preparadas = new CountDownLatch(3);
        CountDownLatch inicio = new CountDownLatch(1);
        try {
            Future<T> resultado1 = executor.submit(() -> ejecutarTrasBarrera(primera, preparadas, inicio));
            Future<T> resultado2 = executor.submit(() -> ejecutarTrasBarrera(segunda, preparadas, inicio));
            Future<T> resultado3 = executor.submit(() -> ejecutarTrasBarrera(tercera, preparadas, inicio));
            assertTrue(preparadas.await(10, TimeUnit.SECONDS));
            inicio.countDown();
            return List.of(resultado1.get(30, TimeUnit.SECONDS), resultado2.get(30, TimeUnit.SECONDS),
                    resultado3.get(30, TimeUnit.SECONDS));
        } finally {
            inicio.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private <T> T ejecutarTrasBarrera(
            Callable<T> operacion,
            CountDownLatch preparadas,
            CountDownLatch inicio) throws Exception {
        preparadas.countDown();
        assertTrue(inicio.await(10, TimeUnit.SECONDS));
        return operacion.call();
    }

    private InicializarDisponibilidadRequest solicitudMixta(Integer limite) {
        SectorInicializacionRequest numerado = new SectorInicializacionRequest("sector-platea", TipoSector.NUMERADO,
                2, List.of(new AsientoInicializacionRequest(1, 1), new AsientoInicializacionRequest(1, 2)));
        SectorInicializacionRequest noNumerado = new SectorInicializacionRequest("sector-general",
                TipoSector.NO_NUMERADO, 7, List.of());
        return new InicializarDisponibilidadRequest(limite == null ? 4 : limite, List.of(numerado, noNumerado));
    }

    private InicializarDisponibilidadRequest solicitudAlternativa(Integer limite) {
        SectorInicializacionRequest numerado = new SectorInicializacionRequest("sector-alternativo",
                TipoSector.NUMERADO, 2,
                List.of(new AsientoInicializacionRequest(2, 1), new AsientoInicializacionRequest(2, 2)));
        SectorInicializacionRequest noNumerado = new SectorInicializacionRequest("sector-alternativo-general",
                TipoSector.NO_NUMERADO, 7, List.of());
        return new InicializarDisponibilidadRequest(limite == null ? 4 : limite, List.of(numerado, noNumerado));
    }

    private InicializarDisponibilidadRequest solicitudConNumeroInvalido() {
        SectorInicializacionRequest numerado = new SectorInicializacionRequest("sector-invalido",
                TipoSector.NUMERADO, 1, List.of(new AsientoInicializacionRequest(1, 0)));
        return new InicializarDisponibilidadRequest(4, List.of(numerado));
    }

    private void guardarSector(String eventoId, String sectorId, TipoSector tipo, int capacidad) {
        SectorDisponibilidad sector = new SectorDisponibilidad();
        sector.setEventoId(eventoId);
        sector.setSectorId(sectorId);
        sector.setTipo(tipo);
        sector.setCapacidadTotal(capacidad);
        sector.setCantidadReservada(0);
        sector.setCantidadVendida(0);
        sectorRepository.saveAndFlush(sector);
    }

    private void guardarAsiento(String eventoId, String sectorId, int numero) {
        AsientoDisponibilidad asiento = new AsientoDisponibilidad();
        asiento.setEventoId(eventoId);
        asiento.setSectorId(sectorId);
        asiento.setFila(1);
        asiento.setNumero(numero);
        asientoRepository.saveAndFlush(asiento);
    }

    private String estado(String eventoId, String sectorId, int numero) {
        return jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad "
                        + "WHERE evento_id = ? AND sector_id = ? AND fila = 1 AND numero = ?",
                String.class, eventoId, sectorId, numero);
    }

    private long enforcedCheckConstraintCount(String tableName, String constraintName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = ? "
                        + "AND constraint_name = ? AND constraint_type = 'CHECK' AND enforced = 'YES'",
                Long.class, tableName, constraintName);
        return count == null ? 0 : count;
    }

    private void assertEventStatusInsertRejected(String estado) {
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO eventos_disponibilidad "
                        + "(estado_evento, evento_id, fecha_creacion, venta_suspendida) "
                        + "VALUES (?, ?, CURRENT_TIMESTAMP, 0)",
                estado, "evento-estado-no-canonico-" + UUID.randomUUID()));
    }

    private long constraintCount(String tableName, String constraintName, String constraintType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = ? "
                        + "AND constraint_name = ? AND constraint_type = ? AND enforced = 'YES'",
                Long.class, tableName, constraintName, constraintType);
        return count == null ? 0 : count;
    }

    private void assertClientEventInsertRejected(String usuarioId, int reservada, int confirmada) {
        assertThrows(DataAccessException.class,
                () -> insertarCantidad(usuarioId, "evento-constraint-" + UUID.randomUUID(), reservada, confirmada));
    }

    private void insertarCantidad(String usuarioId, String eventoId, int reservada, int confirmada) {
        jdbcTemplate.update("INSERT INTO cantidades_cliente_evento "
                        + "(usuario_id, evento_id, cantidad_reservada, cantidad_confirmada) VALUES (?, ?, ?, ?)",
                usuarioId, eventoId, reservada, confirmada);
    }

    private void guardarEvento(String eventoId) {
        EventoDisponibilidad evento = new EventoDisponibilidad();
        evento.setEventoId(eventoId);
        eventosRepository.saveAndFlush(evento);
    }

    private CrearReservaRequest request(String solicitudId, String sectorId, int fila, int numero) {
        return new CrearReservaRequest(solicitudId,
                List.of(new DetalleReservaSolicitud(sectorId, fila, numero, null)));
    }

    private CrearReservaRequest requestCantidad(String solicitudId, String sectorId, int cantidad) {
        return new CrearReservaRequest(solicitudId,
                List.of(new DetalleReservaSolicitud(sectorId, null, null, cantidad)));
    }

    private void assertSectorInsertRejected(int cantidadReservada, int cantidadVendida, int capacidadTotal) {
        String eventoId = "evento-" + UUID.randomUUID();
        String sectorId = "sector-" + UUID.randomUUID();
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO sectores_disponibilidad "
                        + "(evento_id, sector_id, tipo, capacidad_total, cantidad_reservada, cantidad_vendida) "
                        + "VALUES (?, ?, 'NO_NUMERADO', ?, ?, ?)",
                eventoId, sectorId, capacidadTotal, cantidadReservada, cantidadVendida));
    }

    private boolean eventWaitsForCustomerAggregate() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String sql = "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                + "JOIN performance_schema.data_locks requested "
                + "ON requested.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
                + "AND requested.THREAD_ID = w.REQUESTING_THREAD_ID "
                + "WHERE requested.OBJECT_SCHEMA = DATABASE() "
                + "AND requested.OBJECT_NAME = 'cantidades_cliente_evento'";
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(sql, Integer.class);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private DetalleReserva detalleNumerado(String eventoId, String sectorId, int numero) {
        DetalleReserva detalle = detalleBase(eventoId, sectorId);
        detalle.setFila(1);
        detalle.setNumero(numero);
        detalle.setCantidad(1);
        return detalle;
    }

    private DetalleReserva detalleNoNumerado(String eventoId, String sectorId, int cantidad) {
        DetalleReserva detalle = detalleBase(eventoId, sectorId);
        detalle.setCantidad(cantidad);
        return detalle;
    }

    private DetalleReserva detalleBase(String eventoId, String sectorId) {
        Reserva reserva = new Reserva();
        reserva.setEventoId(eventoId);
        DetalleReserva detalle = new DetalleReserva();
        detalle.setReserva(reserva);
        detalle.setSectorId(sectorId);
        return detalle;
    }

    private static final class TemporaryDatabase {
        private final String adminUrl;
        private final String jdbcUrl;
        private final String schema;
        private final String username;
        private final String password;

        private TemporaryDatabase(String adminUrl, String jdbcUrl, String schema, String username, String password) {
            this.adminUrl = adminUrl;
            this.jdbcUrl = jdbcUrl;
            this.schema = schema;
            this.username = username;
            this.password = password;
        }

        private static TemporaryDatabase createFromApplicationConfiguration() {
            Properties properties = ar.edu.unicen.ms_disponibilidad_reservas.support.IsolatedMySqlConfiguration.load();

            String configuredUrl = properties.getProperty("spring.datasource.url");
            String jdbcPrefix = "jdbc:mysql://";
            if (configuredUrl == null || !configuredUrl.startsWith(jdbcPrefix)) {
                throw new IllegalStateException("La URL configurada no corresponde a MySQL");
            }
            String urlRemainder = configuredUrl.substring(jdbcPrefix.length());
            int slash = urlRemainder.indexOf('/');
            if (slash < 1) {
                throw new IllegalStateException("La URL configurada no contiene host y puerto MySQL");
            }
            String hostAndPort = urlRemainder.substring(0, slash);
            String suffix = urlRemainder.substring(slash + 1);
            int queryStart = suffix.indexOf('?');
            String query = queryStart >= 0 ? suffix.substring(queryStart) : "";
            String adminUrl = jdbcPrefix + hostAndPort + "/" + query;
            String schema = "ms2_tarea13_" + UUID.randomUUID().toString().replace("-", "");
            String jdbcUrl = jdbcPrefix + hostAndPort + "/" + schema + query;
            String username = properties.getProperty("spring.datasource.username");
            String password = properties.getProperty("spring.datasource.password", "");

            TemporaryDatabase database = new TemporaryDatabase(adminUrl, jdbcUrl, schema, username, password);
            database.createEmptySchema();
            Runtime.getRuntime().addShutdownHook(new Thread(database::drop));
            return database;
        }

        private void createEmptySchema() {
            try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
                    var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
            } catch (SQLException e) {
                throw new IllegalStateException("No se pudo crear el esquema MySQL temporal de pruebas", e);
            }
        }

        private void drop() {
            try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
                    var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            } catch (SQLException ignored) {
                // El esquema es único para esta ejecución y solo contiene datos de prueba.
            }
        }
    }
}
