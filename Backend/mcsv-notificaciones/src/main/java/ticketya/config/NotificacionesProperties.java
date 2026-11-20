package ticketya.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;

/** Configuración del servicio (prefijo "notificaciones"), cargada desde variables de entorno. */
@Validated
@ConfigurationProperties(prefix = "notificaciones")
public record NotificacionesProperties(
        @Valid @NotNull Correo correo,
        @Valid @NotNull Usuarios usuarios,
        @Valid @NotNull Reintentos reintentos,
        @Valid @NotNull Envio envio,
        @Valid @NotNull Seguridad seguridad) {

    /**
     * Autorización entre servicios (provisoria; mecanismo a acordar con el grupo). Si el token
     * no está configurado, el endpoint interno rechaza todas las llamadas.
     */
    public record Seguridad(String tokenServicio) {

        public boolean tokenConfigurado() {
            return tokenServicio != null && !tokenServicio.isBlank();
        }
    }

    public record Correo(@NotBlank String remitente) {
    }

    public record Usuarios(@NotBlank String url, @NotNull Duration timeoutConexion,
                           @NotNull Duration timeoutLectura) {
    }

    public record Reintentos(@Min(1) int maximoIntentos, @NotNull Duration esperaBase,
                             @NotNull Duration esperaMaxima) {
    }

    public record Envio(boolean activo, @NotNull Duration intervalo, @Min(1) int tamanioLote,
                        @NotNull Duration plazoProcesamiento, @NotNull Duration intervaloRecuperacion) {
    }
}
