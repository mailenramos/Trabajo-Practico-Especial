package ar.edu.unicen.ms_disponibilidad_reservas.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DevolucionRepository {

    private final JdbcTemplate jdbcTemplate;

    public DevolucionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RegistroDevolucion> findById(String devolucionId) {
        return jdbcTemplate.query("SELECT id, reserva_id, huella_solicitud FROM devoluciones WHERE id = ?",
                (rs, row) -> new RegistroDevolucion(
                        rs.getString("id"), rs.getString("reserva_id"), rs.getString("huella_solicitud")),
                devolucionId).stream().findFirst();
    }

    /** Current locking read, used after the reservation lock to see a committed concurrent retry. */
    public Optional<RegistroDevolucion> findByIdForUpdate(String devolucionId) {
        return jdbcTemplate.query("SELECT id, reserva_id, huella_solicitud FROM devoluciones WHERE id = ? FOR UPDATE",
                (rs, row) -> new RegistroDevolucion(
                        rs.getString("id"), rs.getString("reserva_id"), rs.getString("huella_solicitud")),
                devolucionId).stream().findFirst();
    }

    public void insert(String devolucionId, String reservaId, String huella) {
        jdbcTemplate.update("INSERT INTO devoluciones (id, reserva_id, huella_solicitud) VALUES (?, ?, ?)",
                devolucionId, reservaId, huella);
    }

    public boolean seatWasReturned(long detalleReservaId) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM devolucion_detalles_asientos WHERE detalle_reserva_id = ? FOR UPDATE",
                (rs, row) -> rs.getLong(1), detalleReservaId);
        return !ids.isEmpty();
    }

    /** Locking/current read so partial returns are checked against committed refund rows. */
    public int returnedQuantity(long detalleReservaId) {
        List<Integer> quantities = jdbcTemplate.query(
                "SELECT cantidad FROM devolucion_detalles_cantidades WHERE detalle_reserva_id = ? ORDER BY id FOR UPDATE",
                (rs, row) -> rs.getInt(1), detalleReservaId);
        int total = 0;
        for (Integer quantity : quantities) {
            total = Math.addExact(total, quantity);
        }
        return total;
    }

    public void insertReturnedSeat(String devolucionId, String reservaId, long detalleReservaId) {
        jdbcTemplate.update("INSERT INTO devolucion_detalles_asientos "
                + "(devolucion_id, reserva_id, detalle_reserva_id) VALUES (?, ?, ?)",
                devolucionId, reservaId, detalleReservaId);
    }

    public void insertReturnedQuantity(String devolucionId, String reservaId, long detalleReservaId, int cantidad) {
        jdbcTemplate.update("INSERT INTO devolucion_detalles_cantidades "
                + "(devolucion_id, reserva_id, detalle_reserva_id, cantidad) VALUES (?, ?, ?, ?)",
                devolucionId, reservaId, detalleReservaId, cantidad);
    }

    public record RegistroDevolucion(String devolucionId, String reservaId, String huella) {
    }
}
