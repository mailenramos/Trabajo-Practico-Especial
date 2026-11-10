package ticketya.dobles;

import ticketya.cliente.EventosCliente;
import ticketya.cliente.ServicioExternoException;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Doble de prueba del servicio de eventos: guarda los eventos en memoria. Como el servicio
 * real, devuelve el estado en minúsculas (confirmado por Eventos).
 */
public class EventosFalso implements EventosCliente {

    public static final Set<String> ESTADOS =
            Set.of("borrador", "publicado", "agotado", "cancelado", "finalizado");

    private final Map<String, Evento> eventos = new ConcurrentHashMap<>();
    private volatile boolean caido;

    public void agregar(Evento evento) {
        if (!ESTADOS.contains(evento.estado())) {
            throw new IllegalArgumentException("Estado de evento no confirmado por Eventos: " + evento.estado());
        }
        eventos.put(evento.eventoId(), evento);
    }

    public void setCaido(boolean caido) {
        this.caido = caido;
    }

    public void reiniciar() {
        eventos.clear();
        caido = false;
    }

    @Override
    public Optional<Evento> obtenerEvento(String eventoId) {
        if (caido) {
            throw new ServicioExternoException("Eventos", "Eventos no responde (doble)");
        }
        return Optional.ofNullable(eventos.get(eventoId));
    }
}
