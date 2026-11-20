package ticketya.cliente;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Servicio 1 (eventos y lugares): este servicio solo consulta eventos y precios. */
public interface EventosCliente {

    /** Devuelve el evento en cualquier estado, o vacío si no existe. */
    Optional<Evento> obtenerEvento(String eventoId);

    /** {@code tipo} (RECITAL, TEATRO, ...) puede venir vacío: Eventos todavía no confirmó el campo. */
    record Evento(String eventoId, String nombre, Instant fechaInicio, String estado, List<Sector> sectores,
                  String tipo) {

        public Evento(String eventoId, String nombre, Instant fechaInicio, String estado, List<Sector> sectores) {
            this(eventoId, nombre, fechaInicio, estado, sectores, null);
        }

        public Optional<Sector> sector(String sectorId) {
            return sectores == null ? Optional.empty()
                    : sectores.stream().filter(s -> s.sectorId().equals(sectorId)).findFirst();
        }
    }

    record Sector(String sectorId, String nombre, BigDecimal precio) {
    }
}
