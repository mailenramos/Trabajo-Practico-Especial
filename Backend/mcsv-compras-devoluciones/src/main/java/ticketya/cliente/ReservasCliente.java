package ticketya.cliente;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Servicio 2 (disponibilidad y reservas). */
public interface ReservasCliente {

    /** Devuelve la reserva, o vacío si no existe. */
    Optional<Reserva> obtenerReserva(String reservaId);

    /**
     * Confirma la venta de los lugares. Es repetible: si ya estaba confirmada no la
     * cuenta de nuevo.
     *
     * @throws ConfirmacionRechazadaException si la reserva venció o se liberó
     */
    void confirmarVenta(String reservaId);

    /** Libera la reserva completa porque el pago fue rechazado. Es repetible. */
    void liberarPorPagoRechazado(String reservaId);

    /**
     * Libera solo el lugar devuelto. Es repetible por devolucionId: el mismo devolucionId
     * no libera dos veces.
     */
    void liberarPorDevolucion(String reservaId, String devolucionId, LugarDevuelto lugar);

    /**
     * Inventario actual del evento ({@code GET /disponibilidad/eventos/{eventoId}}), para el
     * reporte de ocupación. Vacío si el evento no tiene inventario (404).
     */
    Optional<Disponibilidad> obtenerDisponibilidad(String eventoId);

    record Disponibilidad(String eventoId, List<SectorDisponibilidad> sectores) {
    }

    /** Por sector: capacidad total, lugares vendidos y reservados (retenidos hasta su liberación). */
    record SectorDisponibilidad(String sectorId, Integer capacidadTotal, Integer cantidadVendida,
                                Integer cantidadReservada) {
    }

    /** Un lugar numerado (fila y número) o una unidad de un sector no numerado. */
    record LugarDevuelto(String sectorId, Integer fila, Integer numero) {
        public boolean numerado() {
            return fila != null || numero != null;
        }
    }

    enum EstadoReserva { VIGENTE, CONFIRMADA, LIBERADA }

    record Reserva(String reservaId, String clienteId, String eventoId, EstadoReserva estado,
                   Instant expiraEn, List<DetalleReserva> detalles) {

        /** Vigente según Reservas y todavía sin vencer. */
        public boolean estaVigente(Instant ahora) {
            return estado == EstadoReserva.VIGENTE && (expiraEn == null || expiraEn.isAfter(ahora));
        }
    }

    /** Un lugar numerado (fila y número) o una cantidad de un sector no numerado. */
    record DetalleReserva(String sectorId, Integer fila, Integer numero, Integer cantidad) {
    }

    /** Reservas rechazó la confirmación: RESERVA_VENCIDA o RESERVA_LIBERADA. */
    class ConfirmacionRechazadaException extends RuntimeException {
        private final String codigo;

        public ConfirmacionRechazadaException(String codigo) {
            super("Reservas rechazó la confirmación de la venta: " + codigo);
            this.codigo = codigo;
        }

        public String getCodigo() { return codigo; }
    }
}
