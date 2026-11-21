package ar.edu.unicen.ms_fila_virtual.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(Instant timestamp, int status, String error, String mensaje, List<CampoError> campos) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CampoError(String campo, String mensaje) {
    }
}
