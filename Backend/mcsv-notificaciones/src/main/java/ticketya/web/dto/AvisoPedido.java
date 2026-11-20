package ticketya.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ticketya.modelo.TipoNotificacion;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Aviso de un acontecimiento enviado por otro servicio. La forma exacta y los campos de
 * {@code datos} por tipo son contrato a acordar con el grupo.
 *
 * @param acontecimientoId identificador estable del acontecimiento (el mismo en cada reentrega)
 * @param tipo             tipo de notificación (ver {@link TipoNotificacion})
 * @param versionEsquema   versión del esquema del aviso
 * @param ocurridoEn       fecha y hora del acontecimiento
 * @param recursoId        recurso relacionado (compra, evento, devolución, reintegro)
 * @param usuarioId        usuario destinatario
 * @param correlacionId    identificador de correlación para seguir el pedido entre servicios
 * @param datos            datos para generar el correo
 */
public record AvisoPedido(
        @NotBlank(message = "es obligatorio") @Size(max = 100, message = "admite hasta 100 caracteres")
        String acontecimientoId,

        @NotBlank(message = "es obligatorio")
        @ValorDeEnum(value = TipoNotificacion.class, message = "tipo de notificación desconocido")
        String tipo,

        @NotNull(message = "es obligatorio")
        Integer versionEsquema,

        @NotNull(message = "es obligatorio")
        Instant ocurridoEn,

        @NotBlank(message = "es obligatorio") @Size(max = 100, message = "admite hasta 100 caracteres")
        String recursoId,

        @NotBlank(message = "es obligatorio") @Size(max = 100, message = "admite hasta 100 caracteres")
        String usuarioId,

        @NotBlank(message = "es obligatorio") @Size(max = 100, message = "admite hasta 100 caracteres")
        String correlacionId,

        @NotNull(message = "es obligatorio")
        Map<String, Object> datos) {

    /** Versiones del esquema del aviso que este servicio sabe procesar. */
    public static final Set<Integer> VERSIONES_SOPORTADAS = Set.of(1);

    public TipoNotificacion tipoNotificacion() {
        return TipoNotificacion.valueOf(tipo);
    }
}
