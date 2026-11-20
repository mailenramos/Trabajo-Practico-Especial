package ticketya.web;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Usuario que hace el pedido: su id ({@code X-User-Id}, el sub del JWT que valida el Gateway) y
 * sus roles ({@code X-User-Roles}). Los roles se guardan normalizados: en mayúsculas, sin
 * espacios y sin vacíos. Un conjunto vacío es válido (usuario sin roles).
 */
public record UsuarioActual(String id, Set<String> roles) {

    public UsuarioActual {
        roles = normalizar(roles);
    }

    /** Arma el usuario a partir de una lista de roles separados por coma (puede ser nula). */
    public static UsuarioActual conRolesSeparadosPorComa(String id, String roles) {
        return new UsuarioActual(id, roles == null ? Set.of()
                : Arrays.stream(roles.split(",")).collect(Collectors.toSet()));
    }

    /** True si el usuario tiene el rol, sin distinguir mayúsculas ni espacios. */
    public boolean tieneRol(String rol) {
        String buscado = normalizar(rol);
        return buscado != null && roles.contains(buscado);
    }

    private static Set<String> normalizar(Collection<String> roles) {
        if (roles == null) {
            return Set.of();
        }
        return roles.stream()
                .map(UsuarioActual::normalizar)
                .filter(r -> r != null)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Mayúsculas y sin espacios; nulo si queda vacío. */
    private static String normalizar(String rol) {
        if (rol == null) {
            return null;
        }
        String limpio = rol.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return limpio.isEmpty() ? null : limpio;
    }
}
