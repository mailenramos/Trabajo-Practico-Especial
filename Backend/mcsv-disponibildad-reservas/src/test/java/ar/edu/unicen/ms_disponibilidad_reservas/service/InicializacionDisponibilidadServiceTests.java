package ar.edu.unicen.ms_disponibilidad_reservas.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import ar.edu.unicen.ms_disponibilidad_reservas.dto.AsientoInicializacionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.InicializarDisponibilidadRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.SectorInicializacionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.model.AsientoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.EventoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.SectorDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.AsientoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.EventoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.SectorDisponibilidadRepository;
import jakarta.validation.ConstraintViolationException;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Import({ InicializacionDisponibilidadService.class, InicializacionDisponibilidadServiceTests.ValidationConfig.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
class InicializacionDisponibilidadServiceTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class ValidationConfig {
        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }
    }

    @Autowired
    private InicializacionDisponibilidadService inicializacionService;

    @Autowired
    private EventoDisponibilidadRepository eventosRepository;

    @Autowired
    private SectorDisponibilidadRepository sectoresRepository;

    @Autowired
    private AsientoDisponibilidadRepository asientosRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void limpiarDatos() {
        jdbcTemplate.update("DELETE FROM detalles_reserva");
        jdbcTemplate.update("DELETE FROM reservas");
        jdbcTemplate.update("DELETE FROM cantidades_cliente_evento");
        jdbcTemplate.update("DELETE FROM asientos_disponibilidad");
        jdbcTemplate.update("DELETE FROM sectores_disponibilidad");
        jdbcTemplate.update("DELETE FROM eventos_disponibilidad");
    }

    @Test
    void persistsNumberedAndNonNumberedInventoryWithPendingStateAndEffectiveDefaultLimit() {
        EventoDisponibilidad evento = inicializacionService.inicializar("evento-1", solicitudMixta(null));

        EventoDisponibilidad persistido = eventosRepository.findByEventoId("evento-1").orElseThrow();
        assertEquals(evento.getId(), persistido.getId());
        assertEquals(EstadoEvento.PENDIENTE_PUBLICACION, persistido.getEstadoEvento());
        assertEquals(4, persistido.getLimiteEntradasPorCliente());
        assertNotNull(persistido.getHuellaInicializacion());
        assertEquals(2, sectoresRepository.findAll().size());
        assertEquals(2, asientosRepository.findAll().size());
        assertTrue(asientosRepository.findAll().stream()
                .allMatch(asiento -> asiento.getEstado().name().equals("LIBRE")));

        SectorDisponibilidad noNumerado = sectoresRepository.findByEventoIdAndSectorId("evento-1", "sector-general")
                .orElseThrow();
        assertEquals(0, noNumerado.getCantidadReservada());
        assertEquals(0, noNumerado.getCantidadVendida());
        assertEquals(7, noNumerado.getCapacidadTotal());
    }

    @Test
    void estadoEventoSetterRejectsNull() {
        EventoDisponibilidad evento = new EventoDisponibilidad();

        assertThrows(NullPointerException.class, () -> evento.setEstadoEvento(null));
    }

    @Test
    void newEventDefaultsToPendingPublication() {
        assertEquals(EstadoEvento.PENDIENTE_PUBLICACION, new EventoDisponibilidad().getEstadoEvento());
    }

    @Test
    void persistsAndRecoversEveryCanonicalEventStatus() {
        for (EstadoEvento estado : EstadoEvento.values()) {
            EventoDisponibilidad evento = new EventoDisponibilidad();
            evento.setEventoId("evento-estado-" + estado);
            evento.setEstadoEvento(estado);
            EventoDisponibilidad persistido = eventosRepository.saveAndFlush(evento);
            entityManager.clear();

            assertEquals(estado, eventosRepository.findByEventoId(persistido.getEventoId())
                    .orElseThrow().getEstadoEvento());
        }
    }

    @Test
    void persistsTheFixedEventLimitOfFour() {
        EventoDisponibilidad evento = inicializacionService.inicializar("evento-limite", solicitudMixta(4));

        assertEquals(4, eventosRepository.findByEventoId("evento-limite").orElseThrow()
                .getLimiteEntradasPorCliente());
        assertEquals(EstadoEvento.PENDIENTE_PUBLICACION, evento.getEstadoEvento());
    }

    @Test
    void identicalRetryReturnsExistingEventWithoutResettingLaterInventoryChanges() {
        InicializarDisponibilidadRequest request = solicitudMixta(null);
        EventoDisponibilidad original = inicializacionService.inicializar("evento-retry", request);
        assertTrue(original.getHuellaInicializacion().startsWith("v2:"));
        jdbcTemplate.update("UPDATE sectores_disponibilidad SET cantidad_reservada = 1 "
                + "WHERE evento_id = ? AND sector_id = ?", "evento-retry", "sector-general");
        jdbcTemplate.update("UPDATE asientos_disponibilidad SET estado = 'RESERVADO' "
                + "WHERE evento_id = ? AND numero = 1", "evento-retry");

        EventoDisponibilidad reintento = inicializacionService.inicializar("evento-retry", request);

        assertEquals(original.getId(), reintento.getId());
        assertEquals(4, reintento.getLimiteEntradasPorCliente());
        assertEquals(1, sectoresRepository.findByEventoIdAndSectorId("evento-retry", "sector-general")
                .orElseThrow().getCantidadReservada());
        assertEquals("RESERVADO", estadoAsiento("evento-retry", 1));
        assertEquals(2, sectoresRepository.findAll().size());
        assertEquals(2, asientosRepository.findAll().size());
    }

    @Test
    void fingerprintTreatsRowsAsIntegersAndUsesNumericOrdering() {
        SectorInicializacionRequest primeraOrden = new SectorInicializacionRequest("sector-ordenado",
                TipoSector.NUMERADO, 2,
                List.of(new AsientoInicializacionRequest(10, 1), new AsientoInicializacionRequest(2, 1)));
        SectorInicializacionRequest ordenNumerico = new SectorInicializacionRequest("sector-ordenado",
                TipoSector.NUMERADO, 2,
                List.of(new AsientoInicializacionRequest(2, 1), new AsientoInicializacionRequest(10, 1)));

        EventoDisponibilidad original = inicializacionService.inicializar("evento-fingerprint-numerico",
                new InicializarDisponibilidadRequest(4, List.of(primeraOrden)));
        EventoDisponibilidad retry = inicializacionService.inicializar("evento-fingerprint-numerico",
                new InicializarDisponibilidadRequest(4, List.of(ordenNumerico)));

        assertEquals(original.getId(), retry.getId());
        assertTrue(retry.getHuellaInicializacion().matches("v2:[0-9a-f]{64}"));
    }

    @Test
    void legacyUnversionedFingerprintIsNotAcceptedAsVerifiedIdenticalRetry() {
        EventoDisponibilidad evento = new EventoDisponibilidad();
        evento.setEventoId("evento-huella-v1");
        evento.setHuellaInicializacion("a".repeat(64));
        eventosRepository.saveAndFlush(evento);

        InicializacionConflictException exception = assertThrows( InicializacionConflictException.class,
                () -> inicializacionService.inicializar("evento-huella-v1", solicitudMixta(null)));

        assertTrue(exception.getMessage().contains("anterior o no verificable"));
        assertEquals("a".repeat(64), eventosRepository.findByEventoId("evento-huella-v1")
                .orElseThrow().getHuellaInicializacion());
        assertEquals(0, sectoresRepository.count());
        assertEquals(0, asientosRepository.count());
    }

    @Test
    void changedInitializationContentConflictsWithoutModifyingExistingData() {
        InicializarDisponibilidadRequest original = solicitudMixta(null);
        inicializacionService.inicializar("evento-conflictivo", original);
        SectorInicializacionRequest differentCapacity = new SectorInicializacionRequest("sector-general",
                TipoSector.NO_NUMERADO, 11, List.of());
        InicializarDisponibilidadRequest diferente = new InicializarDisponibilidadRequest(4,
                List.of(original.sectores().get(0), differentCapacity));

        assertThrows(InicializacionConflictException.class,
                () -> inicializacionService.inicializar("evento-conflictivo", diferente));

        EventoDisponibilidad persistido = eventosRepository.findByEventoId("evento-conflictivo").orElseThrow();
        assertEquals(4, persistido.getLimiteEntradasPorCliente());
        assertEquals(2, sectoresRepository.findAll().size());
        assertEquals(2, asientosRepository.findAll().size());
    }

    @Test
    void databaseFailureAfterEventAndSectorWritesRollsBackWholeInitialization() {
        jdbcTemplate.execute("ALTER TABLE asientos_disponibilidad "
                + "ADD CONSTRAINT ck_test_rechazar_numero_dos CHECK (numero <> 2)");
        try {
            assertThrows(DataIntegrityViolationException.class,
                    () -> inicializacionService.inicializar("evento-rollback", solicitudMixta(null)));

            assertEquals(0, eventosRepository.count());
            assertEquals(0, sectoresRepository.count());
            assertEquals(0, asientosRepository.count());
        } finally {
            jdbcTemplate.execute("ALTER TABLE asientos_disponibilidad DROP CONSTRAINT ck_test_rechazar_numero_dos");
        }
    }

    @Test
    void validatesIdentifiersInventoryShapeAndPositiveSeatNumbersBeforeWriting() {
        assertThrows(IllegalArgumentException.class,
                () -> inicializacionService.inicializar(" ", solicitudMixta(null)));
        for (Integer invalidLimit : List.of(-1, 0, 1, 3, 5)) {
            assertThrows(ConstraintViolationException.class,
                    () -> inicializacionService.inicializar("evento-limite-invalido-" + invalidLimit,
                            new InicializarDisponibilidadRequest(invalidLimit, solicitudMixta(null).sectores())));
        }
        assertThrows(ConstraintViolationException.class,
                () -> inicializacionService.inicializar("evento-sin-limite",
                        new InicializarDisponibilidadRequest(null, solicitudMixta(null).sectores())));
        EventoDisponibilidad limiteFijo = new EventoDisponibilidad();
        limiteFijo.setLimiteEntradasPorCliente(4);
        assertThrows(IllegalArgumentException.class, () -> limiteFijo.setLimiteEntradasPorCliente(5));
        assertEquals(4, limiteFijo.getLimiteEntradasPorCliente());
        assertThrows(ConstraintViolationException.class,
                () -> inicializacionService.inicializar("evento-numero-invalido", solicitudConNumero(0)));
        assertThrows(ConstraintViolationException.class,
                () -> inicializacionService.inicializar("evento-numero-nulo", solicitudConNumero(null)));
        SectorInicializacionRequest filaNula = new SectorInicializacionRequest("sector-fila-nula",
                TipoSector.NUMERADO, 1, List.of(new AsientoInicializacionRequest(null, 1)));
        assertThrows(ConstraintViolationException.class,
                () -> inicializacionService.inicializar("evento-fila-nula",
                        new InicializarDisponibilidadRequest(4, List.of(filaNula))));
        assertEquals(0, eventosRepository.count());

        SectorInicializacionRequest noNumeradoConAsiento = new SectorInicializacionRequest(
                "sector-general", TipoSector.NO_NUMERADO, 1, List.of(new AsientoInicializacionRequest(1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> inicializacionService.inicializar("evento-forma-invalida",
                        new InicializarDisponibilidadRequest(4, List.of(noNumeradoConAsiento))));

        assertEquals(0, eventosRepository.count());
    }

    @Test
    void filaIsAnIntegerWithoutAnUnspecifiedPositivityRule() {
        SectorInicializacionRequest numerado = new SectorInicializacionRequest("sector-filas-enteras",
                TipoSector.NUMERADO, 2,
                List.of(new AsientoInicializacionRequest(-1, 1), new AsientoInicializacionRequest(0, 2)));

        inicializacionService.inicializar("evento-filas-enteras",
                new InicializarDisponibilidadRequest(4, List.of(numerado)));

        assertEquals(2, asientosRepository.count());
        assertEquals(1, asientosRepository.findAll().stream().filter(asiento -> asiento.getFila() == 0).count());
        assertEquals(1, asientosRepository.findAll().stream().filter(asiento -> asiento.getFila() == -1).count());
    }

    @Test
    void numberedSeatCountDoesNotHaveToEqualDeclaredCapacityUntilTheRuleIsDefined() {
        SectorInicializacionRequest numerado = new SectorInicializacionRequest("sector-capacidad-no-equivalente",
                TipoSector.NUMERADO, 3, List.of(new AsientoInicializacionRequest(1, 1)));

        inicializacionService.inicializar("evento-capacidad-no-equivalente",
                new InicializarDisponibilidadRequest(4, List.of(numerado)));

        assertEquals(3, sectoresRepository.findByEventoIdAndSectorId(
                "evento-capacidad-no-equivalente", "sector-capacidad-no-equivalente").orElseThrow()
                .getCapacidadTotal());
        assertEquals(1, asientosRepository.count());
    }

    @Test
    void rejectsDuplicateSectorIdsAndNumberedPlaceKeysWithinOnePayload() {
        SectorInicializacionRequest primera = new SectorInicializacionRequest("sector-duplicado",
                TipoSector.NUMERADO, 1, List.of(new AsientoInicializacionRequest(1, 1)));
        SectorInicializacionRequest duplicada = new SectorInicializacionRequest("sector-duplicado",
                TipoSector.NUMERADO, 1, List.of(new AsientoInicializacionRequest(2, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> inicializacionService.inicializar("evento-sector-duplicado",
                        new InicializarDisponibilidadRequest(4, List.of(primera, duplicada))));

        SectorInicializacionRequest lugaresDuplicados = new SectorInicializacionRequest("sector-lugares-duplicados",
                TipoSector.NUMERADO, 2,
                List.of(new AsientoInicializacionRequest(1, 1), new AsientoInicializacionRequest(1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> inicializacionService.inicializar("evento-lugar-duplicado",
                        new InicializarDisponibilidadRequest(4, List.of(lugaresDuplicados))));
        assertEquals(0, eventosRepository.count());
    }

    @Test
    void concurrentIdenticalInitializationsProduceOneCompleteEvent() throws Exception {
        InicializarDisponibilidadRequest request = solicitudMixta(null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch preparadas = new CountDownLatch(2);
        CountDownLatch inicio = new CountDownLatch(1);
        try {
            Future<EventoDisponibilidad> primero = executor.submit(() -> ejecutarTrasBarrera(
                    () -> inicializacionService.inicializar("evento-concurrente", request), preparadas, inicio));
            Future<EventoDisponibilidad> segundo = executor.submit(() -> ejecutarTrasBarrera(
                    () -> inicializacionService.inicializar("evento-concurrente", request), preparadas, inicio));
            assertTrue(preparadas.await(10, TimeUnit.SECONDS));
            inicio.countDown();

            EventoDisponibilidad resultado1 = primero.get(30, TimeUnit.SECONDS);
            EventoDisponibilidad resultado2 = segundo.get(30, TimeUnit.SECONDS);
            assertEquals(resultado1.getId(), resultado2.getId());
            assertEquals(1, eventosRepository.count());
            assertEquals(2, sectoresRepository.findAll().size());
            assertEquals(2, asientosRepository.findAll().size());
        } finally {
            inicio.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private EventoDisponibilidad ejecutarTrasBarrera(
            java.util.concurrent.Callable<EventoDisponibilidad> operacion,
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

    private InicializarDisponibilidadRequest solicitudConNumero(int numero) {
        return solicitudConNumero(Integer.valueOf(numero));
    }

    private InicializarDisponibilidadRequest solicitudConNumero(Integer numero) {
        SectorInicializacionRequest numerado = new SectorInicializacionRequest("sector-platea", TipoSector.NUMERADO,
                1, List.of(new AsientoInicializacionRequest(1, numero)));
        return new InicializarDisponibilidadRequest(4, List.of(numerado));
    }

    private String estadoAsiento(String eventoId, int numero) {
        return jdbcTemplate.queryForObject("SELECT estado FROM asientos_disponibilidad "
                + "WHERE evento_id = ? AND sector_id = 'sector-platea' AND fila = 1 AND numero = ?",
                String.class, eventoId, numero);
    }
}
