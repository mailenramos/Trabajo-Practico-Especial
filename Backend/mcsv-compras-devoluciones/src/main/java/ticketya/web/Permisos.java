package ticketya.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ticketya.error.ApiException;

/**
 * Permisos según X-User-Role. El grupo confirmó ADMIN como rol de administrador; se deja
 * configurable con compras.rol-administrador (por defecto ADMIN) y se compara sin
 * distinguir mayúsculas.
 */
@Component
public class Permisos {

    private final String rolAdministrador;

    public Permisos(@Value("${compras.rol-administrador:ADMIN}") String rolAdministrador) {
        this.rolAdministrador = rolAdministrador.trim();
    }

    public boolean esAdministrador(UsuarioActual usuario) {
        return usuario.rol() != null && usuario.rol().equalsIgnoreCase(rolAdministrador);
    }

    /** Solo un administrador puede seguir; si no, 403 SOLO_ADMINISTRADOR. */
    public void exigirAdministrador(UsuarioActual usuario) {
        if (!esAdministrador(usuario)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SOLO_ADMINISTRADOR",
                    "Esta operación solo la puede hacer un administrador");
        }
    }
}
