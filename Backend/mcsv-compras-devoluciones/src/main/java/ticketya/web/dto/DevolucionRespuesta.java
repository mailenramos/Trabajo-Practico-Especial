package ticketya.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.EstadoSolicitud;
import ticketya.modelo.Reintegro;
import ticketya.modelo.SolicitudDevolucion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Solicitud de devolución con el estado de la solicitud y, por separado, el de su
 * reintegro. coordinacion y reintegro aparecen recién cuando la solicitud se aprueba.
 */
public record DevolucionRespuesta(UUID id, EstadoSolicitud estado, String entradaId, UUID compraId,
                                  EventoRespuesta evento, LugarRespuesta lugar, BigDecimal monto,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String motivoRechazo,
                                  Instant creadaEn, Instant resueltaEn,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) CoordinacionRespuesta coordinacion,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) ReintegroResumen reintegro) {

    public record EventoRespuesta(String id, String nombre, Instant fechaInicio) {
    }

    public record LugarRespuesta(String sectorId, String sectorNombre, Integer fila, Integer numero) {
    }

    /** Pasos de una devolución aprobada. pasoPendiente es el primero que falta, o null si está completa. */
    public record CoordinacionRespuesta(boolean completa, PasoDevolucion pasoPendiente, String ultimoError) {
    }

    public record ReintegroResumen(UUID id, EstadoReintegro estado, int intentos) {
    }

    public enum PasoDevolucion { ENTRADA_EN_REINTEGRO, LIBERAR_LUGAR, REINTEGRO, ENTRADA_DEVUELTA }

    /** El primer paso sin hacer de una devolución aprobada, o null si ya está completa. */
    public static PasoDevolucion pasoPendiente(SolicitudDevolucion s, Reintegro r) {
        if (!s.isEntradaEnReintegro()) return PasoDevolucion.ENTRADA_EN_REINTEGRO;
        if (!s.isLugarLiberado()) return PasoDevolucion.LIBERAR_LUGAR;
        if (r == null || r.getEstado() != EstadoReintegro.EXITOSO) return PasoDevolucion.REINTEGRO;
        if (!s.isEntradaDevuelta()) return PasoDevolucion.ENTRADA_DEVUELTA;
        return null;
    }

    public static DevolucionRespuesta de(SolicitudDevolucion s, Reintegro r) {
        CoordinacionRespuesta coordinacion = null;
        if (s.getEstado() == EstadoSolicitud.APROBADA) {
            PasoDevolucion paso = pasoPendiente(s, r);
            String error = s.getUltimoError() != null ? s.getUltimoError() : r == null ? null : r.getUltimoError();
            coordinacion = new CoordinacionRespuesta(paso == null, paso, paso == null ? null : error);
        }
        return new DevolucionRespuesta(s.getId(), s.getEstado(), s.getEntradaId(), s.getCompraId(),
                new EventoRespuesta(s.getEventoId(), s.getEventoNombre(), s.getEventoFechaInicio()),
                new LugarRespuesta(s.getSectorId(), s.getSectorNombre(), s.getFila(), s.getNumero()),
                s.getMonto(), s.getMotivoRechazo(), s.getCreadaEn(), s.getResueltaEn(), coordinacion,
                r == null ? null : new ReintegroResumen(r.getId(), r.getEstado(), r.getIntentos()));
    }
}
