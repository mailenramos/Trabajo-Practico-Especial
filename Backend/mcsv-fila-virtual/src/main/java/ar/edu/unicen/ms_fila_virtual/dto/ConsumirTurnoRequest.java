package ar.edu.unicen.ms_fila_virtual.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Preserves identifiers exactly, including spaces; lengths count Unicode code points. */
public record ConsumirTurnoRequest(
        @NotNull(message = "es obligatorio")
        @Pattern(regexp = "(?s).{1,255}", message = "debe tener entre 1 y 255 caracteres") String usuarioId,
        @NotNull(message = "es obligatorio")
        @Pattern(regexp = "(?s).{1,255}", message = "debe tener entre 1 y 255 caracteres") String solicitudId,
        @NotNull(message = "es obligatorio")
        @Pattern(regexp = "(?s).{1,255}", message = "debe tener entre 1 y 255 caracteres") String huellaSolicitud) {
}
