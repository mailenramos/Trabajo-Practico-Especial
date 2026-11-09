package ar.edu.unicen.ms_disponibilidad_reservas.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoReserva;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.EntityManager;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class ReservaModelTests {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void entitiesExposeTheDesignatedFieldTypes() throws Exception {
        assertEquals(String.class, fieldType(Reserva.class, "id"));
        assertEquals(String.class, fieldType(Reserva.class, "usuarioId"));
        assertEquals(String.class, fieldType(Reserva.class, "eventoId"));
        assertEquals(EstadoReserva.class, fieldType(Reserva.class, "estado"));
        assertEquals(LocalDateTime.class, fieldType(Reserva.class, "expiraEn"));
        assertEquals(String.class, fieldType(Reserva.class, "claveIdempotencia"));
        assertEquals(String.class, fieldType(Reserva.class, "huellaSolicitud"));
        assertEquals(Long.class, fieldType(DetalleReserva.class, "id"));
        assertEquals(String.class, fieldType(DetalleReserva.class, "sectorId"));
        assertEquals(Integer.class, fieldType(DetalleReserva.class, "fila"));
        assertEquals(Integer.class, fieldType(DetalleReserva.class, "numero"));
        assertEquals(Integer.class, fieldType(DetalleReserva.class, "cantidad"));
    }

    @Test
    void idempotencyUniquenessIsScopedByUserAndKey() {
        UniqueConstraint constraint = Arrays.stream(Reserva.class.getAnnotation(Table.class).uniqueConstraints())
                .filter(candidate -> candidate.name().equals("uk_reserva_usuario_clave_idempotencia"))
                .findFirst()
                .orElseThrow();
        assertEquals(Set.of("usuario_id", "clave_idempotencia"),
                Set.copyOf(Arrays.asList(constraint.columnNames())));

        insertReservation("reserva-1", "usuario-1", "clave-1");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertReservation("reserva-2", "usuario-1", "clave-1"));
    }

    @Test
    void reservationIdIsGeneratedAsAUuidStringAndStateIsStoredAsText() {
        Reserva reserva = newReservation("usuario-1", "evento-1", "clave-1");
        entityManager.persist(reserva);
        entityManager.flush();

        assertNotNull(UUID.fromString(reserva.getId()));
        assertEquals("VIGENTE", jdbcTemplate.queryForObject(
                "SELECT estado FROM reservas WHERE id = ?", String.class, reserva.getId()));
    }

    @Test
    void reservationAndDetailsPersistThroughTheLocalForeignKey() {
        Reserva reserva = newReservation("usuario-1", "evento-1", "clave-1");
        DetalleReserva numerado = new DetalleReserva();
        numerado.setSectorId("sector-numerado");
        numerado.setFila(4);
        numerado.setNumero(12);
        numerado.setCantidad(1);
        reserva.agregarDetalle(numerado);

        DetalleReserva noNumerado = new DetalleReserva();
        noNumerado.setSectorId("sector-general");
        noNumerado.setCantidad(3);
        reserva.agregarDetalle(noNumerado);

        entityManager.persist(reserva);
        entityManager.flush();

        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM detalles_reserva WHERE reserva_id = ?", Integer.class, reserva.getId()));
        assertNotNull(numerado.getId());
        assertNotNull(noNumerado.getId());
    }

    @Test
    void hibernateCreatesNamedDetailCheckConstraint() {
        CheckConstraint check = DetalleReserva.class.getAnnotation(Table.class).check()[0];
        assertEquals("ck_detalle_reserva_forma", check.name());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'DETALLES_RESERVA' "
                        + "AND CONSTRAINT_NAME = 'CK_DETALLE_RESERVA_FORMA' "
                        + "AND CONSTRAINT_TYPE = 'CHECK'",
                Integer.class);
        assertEquals(1, count);
    }

    @Test
    void databaseAcceptsBothValidDetailForms() {
        insertReservation("reserva-valid", "usuario-1", "clave-valid");
        insertDetail("reserva-valid", "sector-numerado", 4, 1, 1);
        insertDetail("reserva-valid", "sector-general", null, null, 4);

        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM detalles_reserva", Integer.class));
    }

    @Test
    void databaseRejectsInvalidDetailForms() {
        insertReservation("reserva-invalid", "usuario-1", "clave-invalid");

        assertThrows(DataIntegrityViolationException.class,
                () -> insertDetail("reserva-invalid", "sector-1", 4, 1, 2));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertDetail("reserva-invalid", "sector-2", null, 1, 2));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertDetail("reserva-invalid", "sector-3", 4, null, 1));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertDetail("reserva-invalid", "sector-4", null, null, 0));
    }

    @Test
    void databaseRejectsMissingRequiredDetailValuesAndMissingReservation() {
        insertReservation("reserva-null", "usuario-1", "clave-null");

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO detalles_reserva (reserva_id, sector_id, fila, numero, cantidad) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        "reserva-null", null, null, null, 2));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertDetail("reserva-inexistente", "sector-1", 4, 1, 1));
    }

    private Reserva newReservation(String usuarioId, String eventoId, String claveIdempotencia) {
        Reserva reserva = new Reserva();
        reserva.setUsuarioId(usuarioId);
        reserva.setEventoId(eventoId);
        reserva.setEstado(EstadoReserva.VIGENTE);
        reserva.setExpiraEn(LocalDateTime.of(2026, 11, 6, 12, 0));
        reserva.setClaveIdempotencia(claveIdempotencia);
        reserva.setHuellaSolicitud("huella-" + claveIdempotencia);
        return reserva;
    }

    private Class<?> fieldType(Class<?> entityType, String fieldName) throws NoSuchFieldException {
        Field field = entityType.getDeclaredField(fieldName);
        return field.getType();
    }

    private void insertReservation(String id, String usuarioId, String claveIdempotencia) {
        jdbcTemplate.update(
                "INSERT INTO reservas "
                        + "(id, usuario_id, evento_id, estado, expira_en, clave_idempotencia, huella_solicitud) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, usuarioId, "evento-1", "VIGENTE", LocalDateTime.of(2026, 11, 6, 12, 0),
                claveIdempotencia, "huella-" + claveIdempotencia);
    }

    private void insertDetail(String reservaId, String sectorId, Integer fila, Integer numero, Integer cantidad) {
        jdbcTemplate.update(
                "INSERT INTO detalles_reserva (reserva_id, sector_id, fila, numero, cantidad) "
                        + "VALUES (?, ?, ?, ?, ?)",
                reservaId, sectorId, fila, numero, cantidad);
    }
}
