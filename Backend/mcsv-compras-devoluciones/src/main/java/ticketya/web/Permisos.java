package ticketya.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ticketya.error.ApiException;

/**
 * Permisos según los roles del usuario (X-User-Roles). Es administrador quien tiene, entre sus
 * roles, el configurado en compras.rol-administrador (por defecto ADMIN, confirmado por el
 * grupo). Se decide por pertenencia: no se elige un rol entre varios. Se compara sin distinguir
 * mayúsculas ni espacios.
 */
@Component
public class Permisos {

    private final String rolAdministrador;

    public Permisos(@Value("${compras.rol-administrador:ADMIN}") String rolAdministrador) {
        this.rolAdministrador = rolAdministrador.trim();
    }

    public boolean esAdministrador(UsuarioActual usuario) {
        return usuario.tieneRol(rolAdministrador);
    }

    /** Solo un administrador puede seguir; si no, 403 SOLO_ADMINISTRADOR. */
    public void exigirAdministrador(UsuarioActual usuario) {
        if (!esAdministrador(usuario)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SOLO_ADMINISTRADOR",
                    "Esta operación solo la puede hacer un administrador");
        }
    }
}
