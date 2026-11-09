package ar.edu.unicen.ms_disponibilidad_reservas.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ar.edu.unicen.ms_disponibilidad_reservas.model.AsientoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.DetalleReserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.Reserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.SectorDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoAsiento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.DisponibilidadEventoResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.SectorDisponibilidadResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.persistence.InicializadorEstadoAsientos;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.AsientoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.SectorDisponibilidadRepository;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Import({ DisponibilidadService.class, InicializadorEstadoAsientos.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
class DisponibilidadServiceTests {

    @Autowired
    private DisponibilidadService disponibilidadService;

    @Autowired
    private AsientoDisponibilidadRepository asientoRepository;

    @Autowired
    private SectorDisponibilidadRepository sectorRepository;

    @Autowired
    private InicializadorEstadoAsientos inicializadorEstadoAsientos;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void limpiarDatosDePrueba() {
        jdbcTemplate.update("DELETE FROM detalles_reserva");
        jdbcTemplate.update("DELETE FROM reservas");
        jdbcTemplate.update("DELETE FROM asientos_disponibilidad");
        jdbcTemplate.update("DELETE FROM sectores_disponibilidad");
    }

    @Test
    void newSeatsStartFreeAndDatabaseColumnHasFreeDefault() {
        AsientoDisponibilidad asiento = newSeat("evento-1", "sector-1", 1, 1);
        assertEquals(EstadoAsiento.LIBRE, asiento.getEstado());
        asientoRepository.saveAndFlush(asiento);

        assertEquals("LIBRE", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id = ? AND sector_id = ? AND fila = ? AND numero = ?",
                String.class, "evento-1", "sector-1", 1, 1));

        String columnDefault = jdbcTemplate.queryForObject(
                "SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'ASIENTOS_DISPONIBILIDAD' AND COLUMN_NAME = 'ESTADO'",
                String.class);
        assertTrue(columnDefault.contains("LIBRE"));
    }

    @Test
    void initializerBackfillsBlankLegacyStatesAndPreservesValidStates() {
        insertSeatWithRawState(1, 1, "");
        insertSeatWithRawState(1, 2, "RESERVADO");

        inicializadorEstadoAsientos.afterPropertiesSet();
        inicializadorEstadoAsientos.afterPropertiesSet();

        assertEquals("LIBRE", state(1, 1));
        assertEquals("RESERVADO", state(1, 2));
    }

    @Test
    void initializerRejectsNonCanonicalAndUnknownStatesWithoutChangingThem() {
        insertSeatWithRawState(1, 1, "libre");
        insertSeatWithRawState(1, 2, "DESCONOCIDO");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> inicializadorEstadoAsientos.afterPropertiesSet());

        assertTrue(exception.getMessage().contains("'libre'"));
        assertTrue(exception.getMessage().contains("'DESCONOCIDO'"));
        assertEquals("libre", state(1, 1));
        assertEquals("DESCONOCIDO", state(1, 2));
    }

    @Test
    void numberedAvailabilityCountsOnlyFreeSeatStates() {
        saveSector("evento-1", "sector-n", TipoSector.NUMERADO, 3, 0, 0);
        asientoRepository.saveAllAndFlush(List.of(
                newSeat("evento-1", "sector-n", 1, 1),
                newSeat("evento-1", "sector-n", 1, 2),
                newSeat("evento-1", "sector-n", 1, 3)));

        assertEquals(3, disponibilidadService.consultarDisponibilidad("evento-1", "sector-n"));

        AsientoDisponibilidad reservado = asientoRepository.findAll().stream()
                .filter(a -> a.getNumero().equals(2)).findFirst().orElseThrow();
        reservado.setEstado(EstadoAsiento.RESERVADO);
        asientoRepository.saveAndFlush(reservado);

        assertEquals(2, disponibilidadService.consultarDisponibilidad("evento-1", "sector-n"));
    }

    @Test
    void nonNumberedAvailabilityUsesCapacityMinusReservedAndSold() {
        saveSector("evento-1", "sector-g", TipoSector.NO_NUMERADO, 10, 3, 2);

        assertEquals(5, disponibilidadService.consultarDisponibilidad("evento-1", "sector-g"));
    }

    @Test
    void eventAvailabilityReturnsMultipleSectorTypesAndOnlyFreeNumberedSeats() throws Exception {
        saveSector("evento-1", "sector-n1", TipoSector.NUMERADO, 3, 0, 0);
        saveSector("evento-1", "sector-n2", TipoSector.NUMERADO, 1, 0, 0);
        saveSector("evento-1", "sector-g", TipoSector.NO_NUMERADO, 10, 3, 2);

        AsientoDisponibilidad libre = newSeat("evento-1", "sector-n1", 1, 1);
        AsientoDisponibilidad reservado = newSeat("evento-1", "sector-n1", 1, 2);
        reservado.setEstado(EstadoAsiento.RESERVADO);
        AsientoDisponibilidad vendido = newSeat("evento-1", "sector-n1", 1, 3);
        vendido.setEstado(EstadoAsiento.VENDIDO);
        AsientoDisponibilidad todosNoDisponibles = newSeat("evento-1", "sector-n2", 2, 1);
        todosNoDisponibles.setEstado(EstadoAsiento.RESERVADO);
        asientoRepository.saveAllAndFlush(List.of(libre, reservado, vendido, todosNoDisponibles));

        DisponibilidadEventoResponse response = disponibilidadService.consultarDisponibilidadEvento("evento-1");

        assertEquals("evento-1", response.eventoId());
        assertEquals(3, response.sectores().size());

        SectorDisponibilidadResponse numberedWithFreeSeat = sectorResponse(response, "sector-n1");
        assertEquals(TipoSector.NUMERADO, numberedWithFreeSeat.tipo());
        assertEquals(3, numberedWithFreeSeat.capacidadTotal());
        assertEquals(1, numberedWithFreeSeat.cantidadDisponible());
        assertEquals(1, numberedWithFreeSeat.asientosDisponibles().size());
        assertEquals(1, numberedWithFreeSeat.asientosDisponibles().getFirst().fila());
        assertEquals(1, numberedWithFreeSeat.asientosDisponibles().getFirst().numero());

        SectorDisponibilidadResponse numberedWithoutFreeSeats = sectorResponse(response, "sector-n2");
        assertEquals(0, numberedWithoutFreeSeats.cantidadDisponible());
        assertTrue(numberedWithoutFreeSeats.asientosDisponibles().isEmpty());

        SectorDisponibilidadResponse nonNumbered = sectorResponse(response, "sector-g");
        assertEquals(TipoSector.NO_NUMERADO, nonNumbered.tipo());
        assertEquals(10, nonNumbered.capacidadTotal());
        assertEquals(5, nonNumbered.cantidadDisponible());
        assertNull(nonNumbered.asientosDisponibles());

    }

    @Test
    void numberedAvailabilityIsOrderedByIntegerRowThenSeatNumber() {
        saveSector("evento-orden", "sector-n", TipoSector.NUMERADO, 3, 0, 0);
        asientoRepository.saveAllAndFlush(List.of(
                newSeat("evento-orden", "sector-n", 10, 1),
                newSeat("evento-orden", "sector-n", 2, 8),
                newSeat("evento-orden", "sector-n", 2, 3)));

        var disponibles = disponibilidadService.consultarDisponibilidadEvento("evento-orden")
                .sectores().getFirst().asientosDisponibles();

        assertEquals(List.of(2, 2, 10), disponibles.stream().map(asiento -> asiento.fila()).toList());
        assertEquals(List.of(3, 8, 1), disponibles.stream().map(asiento -> asiento.numero()).toList());
    }

    @Test
    void eventAvailabilityWithoutLocalSectorsReportsNoAvailabilityRegisteredInMs2() {
        DisponibilidadException exception = assertThrows(DisponibilidadException.class,
                () -> disponibilidadService.consultarDisponibilidadEvento("evento-sin-disponibilidad"));

        assertTrue(exception.getMessage().contains("No existe disponibilidad registrada"));
        assertTrue(exception.getMessage().contains("en MS2"));
    }

    private SectorDisponibilidadResponse sectorResponse(DisponibilidadEventoResponse response, String sectorId) {
        return response.sectores().stream()
                .filter(sector -> sector.sectorId().equals(sectorId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void numberedSeatsCanBeReservedConfirmedAndReleasedFromAReservation() {
        saveSector("evento-1", "sector-n", TipoSector.NUMERADO, 2, 0, 0);
        asientoRepository.saveAllAndFlush(List.of(
                newSeat("evento-1", "sector-n", 1, 1),
                newSeat("evento-1", "sector-n", 1, 2)));
        DetalleReserva detalle = numberedDetail("evento-1", "sector-n", 1, 1);

        disponibilidadService.reservar(List.of(detalle));
        assertEquals("RESERVADO", state(1, 1));
        assertEquals(1, disponibilidadService.consultarDisponibilidad("evento-1", "sector-n"));

        disponibilidadService.confirmar(List.of(detalle));
        assertEquals("VENDIDO", state(1, 1));
        assertEquals(1, disponibilidadService.consultarDisponibilidad("evento-1", "sector-n"));

        DetalleReserva segundo = numberedDetail("evento-1", "sector-n", 1, 2);
        disponibilidadService.reservar(List.of(segundo));
        disponibilidadService.liberar(List.of(segundo));
        assertEquals("LIBRE", state(1, 2));
        assertEquals(1, disponibilidadService.consultarDisponibilidad("evento-1", "sector-n"));
    }

    @Test
    void nonNumberedCountersMoveAtomicallyOnReserveConfirmAndRelease() {
        saveSector("evento-1", "sector-g", TipoSector.NO_NUMERADO, 8, 0, 0);
        DetalleReserva detalle = nonNumberedDetail("evento-1", "sector-g", 3);

        disponibilidadService.reservar(List.of(detalle));
        assertSectorCounts("sector-g", 3, 0);
        assertEquals(5, disponibilidadService.consultarDisponibilidad("evento-1", "sector-g"));

        disponibilidadService.confirmar(List.of(detalle));
        assertSectorCounts("sector-g", 0, 3);
        assertEquals(5, disponibilidadService.consultarDisponibilidad("evento-1", "sector-g"));

        DetalleReserva otraReserva = nonNumberedDetail("evento-1", "sector-g", 2);
        disponibilidadService.reservar(List.of(otraReserva));
        disponibilidadService.liberar(List.of(otraReserva));
        assertSectorCounts("sector-g", 0, 3);
        assertEquals(5, disponibilidadService.consultarDisponibilidad("evento-1", "sector-g"));
    }

    @Test
    void insufficientCapacityAndInvalidTransitionsChangeNoCounts() {
        saveSector("evento-1", "sector-g", TipoSector.NO_NUMERADO, 2, 1, 0);
        DetalleReserva detalle = nonNumberedDetail("evento-1", "sector-g", 2);

        assertThrows(DisponibilidadException.class, () -> disponibilidadService.reservar(List.of(detalle)));
        assertSectorCounts("sector-g", 1, 0);

        saveSector("evento-1", "sector-n", TipoSector.NUMERADO, 1, 0, 0);
        asientoRepository.saveAndFlush(newSeat("evento-1", "sector-n", 2, 1));
        DetalleReserva asientoLibre = numberedDetail("evento-1", "sector-n", 2, 1);
        assertThrows(DisponibilidadException.class, () -> disponibilidadService.confirmar(List.of(asientoLibre)));
        assertThrows(DisponibilidadException.class, () -> disponibilidadService.liberar(List.of(asientoLibre)));
        assertEquals("LIBRE", state(2, 1));
    }

    @Test
    void multiSeatReservationRollsBackAllUpdatesWhenOneSeatIsUnavailable() {
        saveSector("evento-1", "sector-n", TipoSector.NUMERADO, 2, 0, 0);
        asientoRepository.saveAllAndFlush(List.of(
                newSeat("evento-1", "sector-n", 1, 1),
                newSeat("evento-1", "sector-n", 1, 2)));
        DetalleReserva a1 = numberedDetail("evento-1", "sector-n", 1, 1);
        DetalleReserva a2 = numberedDetail("evento-1", "sector-n", 1, 2);
        disponibilidadService.reservar(List.of(a2));

        assertThrows(DisponibilidadException.class, () -> disponibilidadService.reservar(List.of(a1, a2)));

        assertEquals("LIBRE", state(1, 1));
        assertEquals("RESERVADO", state(1, 2));
        assertEquals(1, disponibilidadService.consultarDisponibilidad("evento-1", "sector-n"));
    }

    @Test
    void concurrentRequestsCannotReserveTheSameSeat() throws Exception {
        saveSector("evento-1", "sector-n", TipoSector.NUMERADO, 1, 0, 0);
        asientoRepository.saveAndFlush(newSeat("evento-1", "sector-n", 1, 1));

        List<Boolean> results = runConcurrently(
                () -> disponibilidadService.reservar(List.of(numberedDetail("evento-1", "sector-n", 1, 1))),
                () -> disponibilidadService.reservar(List.of(numberedDetail("evento-1", "sector-n", 1, 1))));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals("RESERVADO", state(1, 1));
        assertEquals(0, disponibilidadService.consultarDisponibilidad("evento-1", "sector-n"));
    }

    @Test
    void concurrentRequestsCannotExceedLastNonNumberedCapacity() throws Exception {
        saveSector("evento-1", "sector-g", TipoSector.NO_NUMERADO, 1, 0, 0);

        List<Boolean> results = runConcurrently(
                () -> disponibilidadService.reservar(List.of(nonNumberedDetail("evento-1", "sector-g", 1))),
                () -> disponibilidadService.reservar(List.of(nonNumberedDetail("evento-1", "sector-g", 1))));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertSectorCounts("sector-g", 1, 0);
        assertEquals(0, disponibilidadService.consultarDisponibilidad("evento-1", "sector-g"));
    }

    @Test
    void databaseRejectsSectorCountersAboveCapacity() {
        SectorDisponibilidad sector = newSector("evento-1", "sector-g", TipoSector.NO_NUMERADO, 2, 2, 1);
        assertThrows(DataIntegrityViolationException.class, () -> sectorRepository.saveAndFlush(sector));
    }

    private List<Boolean> runConcurrently(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> result1 = executor.submit(() -> executeAfterReady(first, ready, start));
            Future<Boolean> result2 = executor.submit(() -> executeAfterReady(second, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(result1.get(15, TimeUnit.SECONDS), result2.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private boolean executeAfterReady(Runnable operation, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("No se liberó la barrera de inicio de concurrencia");
        }
        try {
            operation.run();
            return true;
        } catch (DisponibilidadException expectedConflict) {
            return false;
        }
    }

    private void saveSector(String eventoId, String sectorId, TipoSector tipo,
            int capacidad, int reservada, int vendida) {
        sectorRepository.saveAndFlush(newSector(eventoId, sectorId, tipo, capacidad, reservada, vendida));
    }

    private SectorDisponibilidad newSector(String eventoId, String sectorId, TipoSector tipo,
            int capacidad, int reservada, int vendida) {
        SectorDisponibilidad sector = new SectorDisponibilidad();
        sector.setEventoId(eventoId);
        sector.setSectorId(sectorId);
        sector.setTipo(tipo);
        sector.setCapacidadTotal(capacidad);
        sector.setCantidadReservada(reservada);
        sector.setCantidadVendida(vendida);
        return sector;
    }

    private AsientoDisponibilidad newSeat(String eventoId, String sectorId, Integer fila, int numero) {
        AsientoDisponibilidad asiento = new AsientoDisponibilidad();
        asiento.setEventoId(eventoId);
        asiento.setSectorId(sectorId);
        asiento.setFila(fila);
        asiento.setNumero(numero);
        return asiento;
    }

    private DetalleReserva numberedDetail(String eventoId, String sectorId, Integer fila, int numero) {
        DetalleReserva detalle = new DetalleReserva();
        detalle.setReserva(newReservation(eventoId));
        detalle.setSectorId(sectorId);
        detalle.setFila(fila);
        detalle.setNumero(numero);
        detalle.setCantidad(1);
        return detalle;
    }

    private DetalleReserva nonNumberedDetail(String eventoId, String sectorId, int cantidad) {
        DetalleReserva detalle = new DetalleReserva();
        detalle.setReserva(newReservation(eventoId));
        detalle.setSectorId(sectorId);
        detalle.setCantidad(cantidad);
        return detalle;
    }

    private Reserva newReservation(String eventoId) {
        Reserva reserva = new Reserva();
        reserva.setEventoId(eventoId);
        return reserva;
    }

    private String state(Integer fila, int numero) {
        return jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id = 'evento-1' "
                        + "AND sector_id = 'sector-n' AND fila = ? AND numero = ?",
                String.class, fila, numero);
    }

    private void assertSectorCounts(String sectorId, int reservada, int vendida) {
        Integer[] values = jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada, cantidad_vendida FROM sectores_disponibilidad "
                        + "WHERE evento_id = 'evento-1' AND sector_id = ?",
                (rs, rowNum) -> new Integer[] { rs.getInt(1), rs.getInt(2) }, sectorId);
        assertEquals(reservada, values[0]);
        assertEquals(vendida, values[1]);
    }

    private void insertSeatWithRawState(Integer fila, int numero, String estado) {
        jdbcTemplate.update(
                "INSERT INTO asientos_disponibilidad (evento_id, sector_id, fila, numero, estado) "
                        + "VALUES ('evento-1', 'sector-n', ?, ?, ?)",
                fila, numero, estado);
    }
}
