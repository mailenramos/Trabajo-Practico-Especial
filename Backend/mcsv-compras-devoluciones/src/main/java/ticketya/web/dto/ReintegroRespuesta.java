package ticketya.web.dto;

import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Un reintegro, con lo que el administrador necesita para resolverlo. */
public record ReintegroRespuesta(UUID id, UUID compraId, UUID refundRequestId, BigDecimal monto, String motivo,
                                 OrigenReintegro origen, EstadoReintegro estado, int intentos,
                                 String pagosReintegroId, String ultimoError, Instant creadoEn,
                                 Instant actualizadoEn) {

    public static ReintegroRespuesta de(Reintegro r) {
        return new ReintegroRespuesta(r.getId(), r.getCompraId(), r.getRefundRequestId(), r.getMonto(),
                r.getMotivo(), r.getOrigen(), r.getEstado(), r.getIntentos(), r.getPagosReintegroId(),
                r.getUltimoError(), r.getCreadoEn(), r.getActualizadoEn());
    }
}
