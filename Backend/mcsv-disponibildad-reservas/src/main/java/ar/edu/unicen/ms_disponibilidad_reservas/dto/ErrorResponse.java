package ar.edu.unicen.ms_disponibilidad_reservas.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Common HTTP error representation documented for MS2. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String mensaje,
        List<CampoError> campos) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CampoError(String campo, String mensaje) {
    }
}
