package ticketya.cliente.rest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ticketya.cliente.ReservasCliente;
import ticketya.cliente.ServicioExternoException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Contrato confirmado con Reservas (rutas /disponibilidad/reservas/...). */
@Component
public class ReservasClienteRest extends ClienteRest implements ReservasCliente {

    public ReservasClienteRest(@Value("${servicios.reservas.url}") String url,
                               @Value("${servicios.timeout-conexion:2s}") Duration conexion,
                               @Value("${servicios.timeout-lectura:5s}") Duration lectura) {
        super("Reservas", url, conexion, lectura);
    }

    @Override
    public Optional<Reserva> obtenerReserva(String reservaId) {
        return llamar("obtenerReserva",
                () -> Optional.ofNullable(http.get().uri("/disponibilidad/reservas/{id}", reservaId)
                        .retrieve().body(Reserva.class)),
                (status, e) -> status.value() == 404 ? Optional.empty() : null);
    }

    @Override
    public void confirmarVenta(String reservaId) {
        llamar("confirmarVenta", () -> {
            http.post().uri("/disponibilidad/reservas/{id}/confirmacion", reservaId)
                    .retrieve().toBodilessEntity();
            return Boolean.TRUE;
        }, (status, e) -> {
            if (status.value() == 409) {
                String codigo = codigoDeError(e);
                throw new ConfirmacionRechazadaException(codigo != null ? codigo : "RESERVA_NO_VIGENTE");
            }
            if (status.value() == 404) {
                throw new ServicioExternoException("Reservas", "Reservas no encontró la reserva " + reservaId, e);
            }
            return null;
        });
    }

    @Override
    public void liberarPorPagoRechazado(String reservaId) {
        llamar("liberarPorPagoRechazado", () -> {
            http.post().uri("/disponibilidad/reservas/{id}/liberacion", reservaId)
                    .body(Map.of("motivo", "PAGO_RECHAZADO"))
                    .retrieve().toBodilessEntity();
            return Boolean.TRUE;
        });
    }

    /**
     * Liberación parcial por devolución. El lugar va con la misma forma que los detalles de
     * GET /disponibilidad/reservas/{id}: numerado con fila y número; no numerado con
     * cantidad 1 (forma por confirmar con Reservas).
     */
    @Override
    public void liberarPorDevolucion(String reservaId, String devolucionId, LugarDevuelto lugar) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("sectorId", lugar.sectorId());
        if (lugar.numerado()) {
            detalle.put("fila", lugar.fila());
            detalle.put("numero", lugar.numero());
        } else {
            detalle.put("cantidad", 1);
        }
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("motivo", "DEVOLUCION_APROBADA");
        cuerpo.put("devolucionId", devolucionId);
        cuerpo.put("detalles", List.of(detalle));
        llamar("liberarPorDevolucion", () -> {
            http.post().uri("/disponibilidad/reservas/{id}/liberacion", reservaId)
                    .body(cuerpo).retrieve().toBodilessEntity();
            return Boolean.TRUE;
        });
    }
}
