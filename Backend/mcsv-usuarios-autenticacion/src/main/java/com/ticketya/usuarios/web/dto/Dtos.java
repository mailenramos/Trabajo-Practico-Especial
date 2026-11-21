package com.ticketya.usuarios.web.dto;

import com.ticketya.usuarios.modelo.Usuario;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Pedidos y respuestas de la API. Los campos que no figuran aquí (por ejemplo "roles" o "activo"
 * en un registro o en la edición del perfil) se ignoran al deserializar.
 */
public final class Dtos {

    /** Al menos 8 caracteres (máximo 72, límite de BCrypt), con al menos una letra y un número. */
    public static final String REGEX_PASSWORD = "^(?=.*\\p{L})(?=.*\\d).{8,72}$";
    public static final String MENSAJE_PASSWORD =
            "debe tener entre 8 y 72 caracteres, con al menos una letra y un número";

    private Dtos() {
    }

    public static String normalizarCorreo(String correo) {
        return correo == null ? null : correo.trim().toLowerCase(Locale.ROOT);
    }

    private static String recortar(String texto) {
        return texto == null ? null : texto.trim();
    }

    public record RegistroPedido(
            @NotBlank(message = "es obligatorio") @Size(max = 100, message = "admite hasta 100 caracteres")
            String nombre,
            @NotBlank(message = "es obligatorio") @Size(max = 100, message = "admite hasta 100 caracteres")
            String apellido,
            @NotBlank(message = "es obligatorio") @Email(message = "no tiene un formato válido")
            @Size(max = 254, message = "admite hasta 254 caracteres")
            String correo,
            @NotBlank(message = "es obligatoria") @Pattern(regexp = REGEX_PASSWORD, message = MENSAJE_PASSWORD)
            String password) {

        public RegistroPedido {
            nombre = recortar(nombre);
            apellido = recortar(apellido);
            correo = normalizarCorreo(correo);
        }

        @Override
        public String toString() {
            return "RegistroPedido[correo=" + correo + ", password=<oculta>]";
        }
    }

    public record LoginPedido(
            @NotBlank(message = "es obligatorio") String correo,
            @NotBlank(message = "es obligatoria") String password) {

        public LoginPedido {
            correo = normalizarCorreo(correo);
        }

        @Override
        public String toString() {
            return "LoginPedido[correo=" + correo + ", password=<oculta>]";
        }
    }

    public record PerfilPedido(
            @NotBlank(message = "es obligatorio") @Size(max = 100, message = "admite hasta 100 caracteres")
            String nombre,
            @NotBlank(message = "es obligatorio") @Size(max = 100, message = "admite hasta 100 caracteres")
            String apellido) {

        public PerfilPedido {
            nombre = recortar(nombre);
            apellido = recortar(apellido);
        }
    }

    public record RolesPedido(
            @NotEmpty(message = "debe incluir al menos un rol") List<String> roles) {
    }

    public record EstadoPedido(
            @NotNull(message = "es obligatorio") Boolean activo) {
    }

    public record UsuarioRespuesta(
            UUID id,
            String nombre,
            String apellido,
            String correo,
            List<String> roles,
            boolean activo,
            Instant creadoEl) {

        public static UsuarioRespuesta de(Usuario usuario) {
            return new UsuarioRespuesta(
                    usuario.getId(),
                    usuario.getNombre(),
                    usuario.getApellido(),
                    usuario.getCorreo(),
                    rolesOrdenados(usuario),
                    usuario.isActivo(),
                    usuario.getCreadoEl());
        }
    }

    public record TokenRespuesta(String token, String tipoToken, long expiraEn) {
    }

    public record PaginaUsuariosRespuesta(
            List<UsuarioRespuesta> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    public record ContactoRespuesta(String correo) {
    }

    public record IdentidadRespuesta(UUID id, List<String> roles, boolean activo) {

        public static IdentidadRespuesta de(Usuario usuario) {
            return new IdentidadRespuesta(usuario.getId(), rolesOrdenados(usuario), usuario.isActivo());
        }
    }

    private static List<String> rolesOrdenados(Usuario usuario) {
        return usuario.getRoles().stream().map(Enum::name).sorted().toList();
    }
}
