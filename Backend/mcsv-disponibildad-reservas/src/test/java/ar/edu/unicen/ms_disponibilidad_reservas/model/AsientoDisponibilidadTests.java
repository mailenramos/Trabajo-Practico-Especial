package ar.edu.unicen.ms_disponibilidad_reservas.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import ar.edu.unicen.ms_disponibilidad_reservas.dto.AsientoDisponibleResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.AsientoInicializacionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.DisponibilidadEventoResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.SectorDisponibilidadResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.SectorInicializacionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.AsientoDisponibilidadRepository;
import jakarta.persistence.Table;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.UniqueConstraint;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class AsientoDisponibilidadTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AsientoDisponibilidadRepository asientoRepository;

    @Test
    void numberedPlaceFieldsUseTheRequiredTypes() throws Exception {
        assertEquals(String.class, fieldType(EventoDisponibilidad.class, "eventoId"));
        assertEquals(String.class, fieldType(SectorDisponibilidad.class, "eventoId"));
        assertEquals(String.class, fieldType(SectorDisponibilidad.class, "sectorId"));
        assertEquals(String.class, fieldType(AsientoDisponibilidad.class, "eventoId"));
        assertEquals(String.class, fieldType(AsientoDisponibilidad.class, "sectorId"));
        assertEquals(Integer.class, fieldType("fila"));
        assertEquals(Integer.class, fieldType("numero"));
        assertEquals(Long.class, fieldType(AsientoDisponibilidad.class, "id"));
        assertEquals(Long.class, fieldType(SectorDisponibilidad.class, "id"));
        assertEquals(Long.class, fieldType(EventoDisponibilidad.class, "id"));
    }

    @Test
    void availabilityAndInitializationDtosExposeExternalIdsAsStringsAndSeatValuesWithRequiredTypes() {
        assertRecordComponentType(SectorInicializacionRequest.class, "sectorId", String.class);
        assertRecordComponentType(AsientoInicializacionRequest.class, "fila", Integer.class);
        assertRecordComponentType(AsientoInicializacionRequest.class, "numero", Integer.class);
        assertRecordComponentType(DisponibilidadEventoResponse.class, "eventoId", String.class);
        assertRecordComponentType(SectorDisponibilidadResponse.class, "sectorId", String.class);
        assertRecordComponentType(AsientoDisponibleResponse.class, "fila", Integer.class);
        assertRecordComponentType(AsientoDisponibleResponse.class, "numero", Integer.class);
    }

    @Test
    void repositoryPersistsAndRecoversAlphanumericExternalIds() {
        AsientoDisponibilidad seat = new AsientoDisponibilidad();
        seat.setEventoId("EVT-2026-A");
        seat.setSectorId("SEC-PLATEA-01");
        seat.setFila(12);
        seat.setNumero(27);

        AsientoDisponibilidad persisted = asientoRepository.saveAndFlush(seat);
        AsientoDisponibilidad recovered = asientoRepository.findById(persisted.getId()).orElseThrow();

        assertEquals("EVT-2026-A", recovered.getEventoId());
        assertEquals("SEC-PLATEA-01", recovered.getSectorId());
        assertEquals(12, recovered.getFila());
        assertEquals(27, recovered.getNumero());
    }

    @Test
    void uniqueConstraintUsesExactlyTheCompositeBusinessKey() {
        UniqueConstraint constraint = Arrays.stream(AsientoDisponibilidad.class.getAnnotation(Table.class).uniqueConstraints())
                .filter(candidate -> candidate.name().equals("uk_asiento_evento_sector_fila_numero"))
                .findFirst()
                .orElseThrow();

        assertEquals(Set.of("evento_id", "sector_id", "fila", "numero"), Set.copyOf(Arrays.asList(constraint.columnNames())));
    }

    @Test
    void hibernateCreatesTheNamedPositiveNumberCheckConstraint() {
        CheckConstraint constraint = AsientoDisponibilidad.class.getAnnotation(Table.class).check()[0];

        assertEquals("ck_asiento_numero_positivo", constraint.name());
        assertEquals("numero >= 1", constraint.constraint());

        Integer persistedConstraint = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'ASIENTOS_DISPONIBILIDAD' "
                        + "AND CONSTRAINT_NAME = 'CK_ASIENTO_NUMERO_POSITIVO' "
                        + "AND CONSTRAINT_TYPE = 'CHECK'",
                Integer.class);
        assertEquals(1, persistedConstraint);
    }

    @Test
    void databaseRejectsDuplicateCompositeBusinessKey() {
        insert("evento-A", "sector-A", 1, 1);
        assertThrows(DataIntegrityViolationException.class, () -> insert("evento-A", "sector-A", 1, 1));
    }

    @Test
    void databaseAllowsPlacesThatDifferInAnyCompositeKeyComponent() {
        insert("evento-A", "sector-A", 1, 1);
        insert("evento-B", "sector-A", 1, 1);
        insert("evento-A", "sector-B", 1, 1);
        insert("evento-A", "sector-A", 2, 1);
        insert("evento-A", "sector-A", 1, 2);

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asientos_disponibilidad", Integer.class);
        assertEquals(5, count);
    }

    @Test
    void databaseRejectsNonPositivePlaceNumber() {
        assertThrows(DataIntegrityViolationException.class, () -> insert("evento-A", "sector-A", 1, 0));
    }

    @Test
    void databaseAcceptsPositivePlaceNumber() {
        insert("evento-A", "sector-A", 1, 1);

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM asientos_disponibilidad", Integer.class);
        assertEquals(1, count);
    }

    private Class<?> fieldType(Class<?> entityType, String name) throws NoSuchFieldException {
        Field field = entityType.getDeclaredField(name);
        return field.getType();
    }

    private Class<?> fieldType(String name) throws NoSuchFieldException {
        Field field = AsientoDisponibilidad.class.getDeclaredField(name);
        return field.getType();
    }

    private void assertRecordComponentType(Class<?> recordType, String name, Class<?> expectedType) {
        RecordComponent component = Arrays.stream(recordType.getRecordComponents())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
        assertEquals(expectedType, component.getType());
    }

    private void insert(String eventoId, String sectorId, Integer fila, int numero) {
        jdbcTemplate.update(
                "INSERT INTO asientos_disponibilidad (evento_id, sector_id, fila, numero) VALUES (?, ?, ?, ?)",
                eventoId,
                sectorId,
                fila,
                numero);
    }
}
