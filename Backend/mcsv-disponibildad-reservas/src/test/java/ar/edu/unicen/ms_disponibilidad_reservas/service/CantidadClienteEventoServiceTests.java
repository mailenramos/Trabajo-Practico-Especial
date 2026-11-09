package ar.edu.unicen.ms_disponibilidad_reservas.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ar.edu.unicen.ms_disponibilidad_reservas.model.CantidadClienteEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.EventoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.CantidadClienteEventoRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.EventoDisponibilidadRepository;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Import(CantidadClienteEventoService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
class CantidadClienteEventoServiceTests {

    @Autowired
    private CantidadClienteEventoService cantidadesService;

    @Autowired
    private CantidadClienteEventoRepository cantidadesRepository;

    @Autowired
    private EventoDisponibilidadRepository eventosRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void limpiarDatos() {
        jdbcTemplate.update("DELETE FROM cantidades_cliente_evento");
        jdbcTemplate.update("DELETE FROM eventos_disponibilidad");
        guardarEvento("evento-1");
    }

    @Test
    void persistsClientEventIdsAndZeroInitialQuantities() {
        CantidadClienteEvento agregado = cantidadesService.obtenerOCrear("usuario-1", "evento-1");

        assertEquals("usuario-1", agregado.getUsuarioId());
        assertEquals("evento-1", agregado.getEventoId());
        assertEquals(0, agregado.getCantidadReservada());
        assertEquals(0, agregado.getCantidadConfirmada());

        Integer[] persistidos = jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada, cantidad_confirmada FROM cantidades_cliente_evento "
                        + "WHERE usuario_id = ? AND evento_id = ?",
                (rs, rowNum) -> new Integer[] { rs.getInt(1), rs.getInt(2) }, "usuario-1", "evento-1");
        assertEquals(0, persistidos[0]);
        assertEquals(0, persistidos[1]);
    }

    @Test
    void uniqueConstraintPreventsDuplicateClientEventRows() {
        cantidadesRepository.saveAndFlush(new CantidadClienteEvento("usuario-1", "evento-1"));

        assertThrows(DataIntegrityViolationException.class,
                () -> cantidadesRepository.saveAndFlush(new CantidadClienteEvento("usuario-1", "evento-1")));
    }

    @Test
    void databaseRejectsNegativeReservedAndConfirmedQuantities() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertarCantidad("usuario-neg-res", -1, 0));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertarCantidad("usuario-neg-conf", 0, -1));
    }

    @Test
    void repeatedLookupReturnsExistingAggregateWithoutCreatingAnotherRow() {
        CantidadClienteEvento first = cantidadesService.obtenerOCrear("usuario-1", "evento-1");
        CantidadClienteEvento second = cantidadesService.obtenerOCrear("usuario-1", "evento-1");

        assertEquals(first.getId(), second.getId());
        assertEquals(1, cantidadesRepository.countByUsuarioIdAndEventoId("usuario-1", "evento-1"));
    }

    @Test
    void existingAggregateIsRefreshedAfterAcquiringPessimisticLock() {
        CantidadClienteEvento agregado = new CantidadClienteEvento("usuario-1", "evento-1");
        agregado.setCantidadReservada(2);
        agregado.setCantidadConfirmada(3);
        cantidadesRepository.saveAndFlush(agregado);

        TransactionTemplate transaccionExterna = new TransactionTemplate(transactionManager);
        transaccionExterna.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaccionExterna.execute(status -> {
            CantidadClienteEvento administrado = cantidadesRepository
                    .findByUsuarioIdAndEventoId("usuario-1", "evento-1").orElseThrow();

            TransactionTemplate actualizacionConcurrente = new TransactionTemplate(transactionManager);
            actualizacionConcurrente.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            actualizacionConcurrente.execute(innerStatus -> jdbcTemplate.update(
                    "UPDATE cantidades_cliente_evento SET cantidad_reservada = 8, cantidad_confirmada = 9 "
                            + "WHERE usuario_id = 'usuario-1' AND evento_id = 'evento-1'"));

            assertEquals(2, administrado.getCantidadReservada());
            assertEquals(3, administrado.getCantidadConfirmada());

            CantidadClienteEvento actualizada = cantidadesService.obtenerOCrear("usuario-1", "evento-1");

            assertNotSame(administrado, actualizada);
            assertEquals(8, actualizada.getCantidadReservada());
            assertEquals(9, actualizada.getCantidadConfirmada());
            return null;
        });
    }

    @Test
    void concurrentFirstLookupsCreateOnlyOneClientEventRow() throws Exception {
        List<Long> ids = obtenerConcurrentemente("usuario-concurrente", "evento-1");

        assertEquals(ids.get(0), ids.get(1));
        assertEquals(1, cantidadesRepository.countByUsuarioIdAndEventoId("usuario-concurrente", "evento-1"));
    }

    @Test
    void cannotCreateAggregateWithoutInitializedEvent() {
        assertThrows(IllegalArgumentException.class,
                () -> cantidadesService.obtenerOCrear("usuario-1", "evento-inexistente"));
        assertEquals(0, cantidadesRepository.countByUsuarioIdAndEventoId("usuario-1", "evento-inexistente"));
    }

    private void insertarCantidad(String usuarioId, int reservada, int confirmada) {
        jdbcTemplate.update("INSERT INTO cantidades_cliente_evento "
                + "(usuario_id, evento_id, cantidad_reservada, cantidad_confirmada) VALUES (?, 'evento-1', ?, ?)",
                usuarioId, reservada, confirmada);
    }

    private void guardarEvento(String eventoId) {
        EventoDisponibilidad evento = new EventoDisponibilidad();
        evento.setEventoId(eventoId);
        eventosRepository.saveAndFlush(evento);
    }

    private List<Long> obtenerConcurrentemente(String usuarioId, String eventoId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch preparados = new CountDownLatch(2);
        CountDownLatch inicio = new CountDownLatch(1);
        try {
            Future<Long> first = executor.submit(() -> obtenerTrasBarrera(usuarioId, eventoId, preparados, inicio));
            Future<Long> second = executor.submit(() -> obtenerTrasBarrera(usuarioId, eventoId, preparados, inicio));
            assertTrue(preparados.await(5, TimeUnit.SECONDS));
            inicio.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            inicio.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Long obtenerTrasBarrera(String usuarioId, String eventoId,
            CountDownLatch preparados, CountDownLatch inicio) throws Exception {
        preparados.countDown();
        if (!inicio.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("No se liberó la barrera de inicio de concurrencia");
        }
        return cantidadesService.obtenerOCrear(usuarioId, eventoId).getId();
    }
}
