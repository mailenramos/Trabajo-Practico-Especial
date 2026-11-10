package ticketya.cliente.rest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ticketya.cliente.EventosCliente;

import java.time.Duration;
import java.util.Optional;

/** Contrato confirmado: GET /internal/eventos/{eventoId} (200 o 404), sin encabezados de usuario. */
@Component
public class EventosClienteRest extends ClienteRest implements EventosCliente {

    public EventosClienteRest(@Value("${servicios.eventos.url}") String url,
                              @Value("${servicios.timeout-conexion:2s}") Duration conexion,
                              @Value("${servicios.timeout-lectura:5s}") Duration lectura) {
        super("Eventos", url, conexion, lectura);
    }

    @Override
    public Optional<Evento> obtenerEvento(String eventoId) {
        return llamar("obtenerEvento",
                () -> Optional.ofNullable(http.get().uri("/internal/eventos/{id}", eventoId)
                        .retrieve().body(Evento.class)),
                (status, e) -> status.value() == 404 ? Optional.empty() : null);
    }
}
