package ticketya.cliente;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Servicio 5 (entradas). La emisión es idempotente por idCompra. */
public interface EntradasCliente {

    /** Emite una entrada por lugar. Repetirla con el mismo idCompra devuelve las mismas entradas. */
    List<EntradaEmitida> emitir(SolicitudEmision solicitud);

    /** Titular, estado y fecha de inicio del evento de una entrada, o vacío si no existe. */
    Optional<Entrada> consultar(String idEntrada);

    /** Pasa la entrada a en reintegro e invalida su QR. */
    void pasarAEnReintegro(String idEntrada);

    /** Pasa la entrada a devuelta. */
    void pasarADevuelta(String idEntrada);

    /**
     * Pasa a en reintegro las entradas vigentes de un evento cancelado. Devuelve cuántas
     * marcó (sin sus IDs); repetirla responde 0 porque ya no quedan vigentes.
     */
    int marcarEntradasDelEvento(String idEvento);

    enum EstadoEntrada {
        VIGENTE, EN_REINTEGRO, DEVUELTA, DESCONOCIDO;

        /** Interpreta el estado sin depender de mayúsculas, espacios ni guiones. */
        public static EstadoEntrada de(String texto) {
            if (texto == null) {
                return DESCONOCIDO;
            }
            String normalizado = texto.trim().toUpperCase().replace(' ', '_').replace('-', '_');
            for (EstadoEntrada e : values()) {
                if (e.name().equals(normalizado)) {
                    return e;
                }
            }
            return DESCONOCIDO;
        }
    }

    /**
     * Entrada según GET /internal/entradas/{id}. El sector sale del objeto anidado
     * {@code sector} ({@code sector.id}, {@code sector.nombre}); fila y numero son nulos en un
     * sector no numerado.
     */
    record Entrada(String idEntrada, String idCliente, String idCompra, EstadoEntrada estado,
                   String idEvento, Instant comienzaEn, String sectorId, String sectorNombre,
                   Integer fila, Integer numero, String codigoQr) {

        /** Solo con los datos que usan las validaciones (titular, estado y fecha del evento). */
        public Entrada(String idEntrada, String idCliente, EstadoEntrada estado, Instant comienzaEn) {
            this(idEntrada, idCliente, null, estado, null, comienzaEn, null, null, null, null, null);
        }

        /** La misma entrada en otro estado. */
        public Entrada conEstado(EstadoEntrada nuevo) {
            return new Entrada(idEntrada, idCliente, idCompra, nuevo, idEvento, comienzaEn,
                    sectorId, sectorNombre, fila, numero, codigoQr);
        }
    }

    /** Pedido de emisión. Los nombres de los campos son los del contrato de Entradas. */
    record SolicitudEmision(String idCompra, String idCliente, EventoEmision evento, List<LineaEmision> lugares) {
    }

    record EventoEmision(String id, String nombre, Instant comienzaEn) {
    }

    /** Un lugar comprado. En un sector no numerado, fila y numero van en null (una línea por lugar). */
    record LineaEmision(String idSector, String nombreSector, Integer fila, Integer numero) {
    }

    record EntradaEmitida(String idEntrada, String sectorId, Integer fila, Integer numero, String codigoQr) {
    }
}
