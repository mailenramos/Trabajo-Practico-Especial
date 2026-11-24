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

    static final String ROL_ORGANIZADOR_POR_DEFECTO = "ORGANIZADOR";

    private final String rolAdministrador;
    private final String rolOrganizador;

    @org.springframework.beans.factory.annotation.Autowired
    public Permisos(@Value("${compras.rol-administrador:ADMIN}") String rolAdministrador,
                    @Value("${compras.rol-organizador:" + ROL_ORGANIZADOR_POR_DEFECTO + "}") String rolOrganizador) {
        this.rolAdministrador = rolAdministrador.trim();
        this.rolOrganizador = rolOrganizador.trim();
    }

    /** Con el rol organizador por defecto. */
    public Permisos(String rolAdministrador) {
        this(rolAdministrador, ROL_ORGANIZADOR_POR_DEFECTO);
    }

    /**
     * Reportes de un evento, primer control (sin consultar a Eventos): hace falta el rol
     * administrador u organizador; si no, 403 SIN_PERMISO_REPORTES.
     */
    public void exigirRolDeReportes(UsuarioActual usuario) {
        if (!esAdministrador(usuario) && !usuario.tieneRol(rolOrganizador)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SIN_PERMISO_REPORTES",
                    "Los reportes los consulta el organizador del evento o un administrador");
        }
    }

    /**
     * Reportes de un evento, segundo control: un administrador ve cualquier evento; un organizador,
     * solo si es el titular ({@code organizadorId} según Eventos). Si no, 403 EVENTO_AJENO.
     */
    public void exigirTitularidadDeEvento(UsuarioActual usuario, String organizadorId) {
        exigirRolDeReportes(usuario);
        if (esAdministrador(usuario)) {
            return;
        }
        if (organizadorId == null || !organizadorId.equals(usuario.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EVENTO_AJENO",
                    "El evento pertenece a otro organizador");
        }
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
