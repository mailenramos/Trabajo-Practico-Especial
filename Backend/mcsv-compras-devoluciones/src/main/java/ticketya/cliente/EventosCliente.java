package ticketya.cliente;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Servicio 1 (eventos y lugares): este servicio solo consulta eventos y precios. */
public interface EventosCliente {

    /** Devuelve el evento en cualquier estado, o vacío si no existe. */
    Optional<Evento> obtenerEvento(String eventoId);

    record Evento(String eventoId, String nombre, Instant fechaInicio, String estado, List<Sector> sectores) {

        public Optional<Sector> sector(String sectorId) {
            return sectores == null ? Optional.empty()
                    : sectores.stream().filter(s -> s.sectorId().equals(sectorId)).findFirst();
        }
    }

    record Sector(String sectorId, String nombre, BigDecimal precio) {
    }
}
