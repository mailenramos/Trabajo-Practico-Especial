package ticketya.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import ticketya.error.ApiException;

/**
 * Arma el {@link UsuarioActual} de cada pedido a partir de los encabezados que propaga el API
 * Gateway después de validar el JWT. Este servicio no recibe ni valida el token.
 * <ul>
 *   <li>{@value #ENCABEZADO_USUARIO}: obligatorio; sin él, 401 USUARIO_NO_IDENTIFICADO.</li>
 *   <li>{@value #ENCABEZADO_ROLES}: roles separados por coma; puede haber varios o ninguno.</li>
 *   <li>{@value #ENCABEZADO_ROL}: rol único anterior, solo si no llega {@value #ENCABEZADO_ROLES}
 *       (compatibilidad durante la transición; si llegan los dos, manda el nuevo).</li>
 * </ul>
 * Los valores de estos encabezados no se registran en los logs.
 */
public class UsuarioActualResolver implements HandlerMethodArgumentResolver {

    public static final String ENCABEZADO_USUARIO = "X-User-Id";
    public static final String ENCABEZADO_ROLES = "X-User-Roles";
    /** Obsoleto: se acepta como respaldo hasta que todo el grupo use el Gateway con JWT. */
    public static final String ENCABEZADO_ROL = "X-User-Role";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(UsuarioActual.class);
    }

    @Override
    public UsuarioActual resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                         NativeWebRequest request, WebDataBinderFactory binderFactory) {
        String id = request.getHeader(ENCABEZADO_USUARIO);
        if (id == null || id.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "USUARIO_NO_IDENTIFICADO",
                    "Falta el encabezado " + ENCABEZADO_USUARIO);
        }
        String roles = request.getHeader(ENCABEZADO_ROLES);
        if (roles == null) {
            // Un único rol del encabezado anterior. No se parte por comas: es un solo valor
            String rol = request.getHeader(ENCABEZADO_ROL);
            return new UsuarioActual(id.trim(), rol == null ? java.util.Set.of() : java.util.Set.of(rol));
        }
        return UsuarioActual.conRolesSeparadosPorComa(id.trim(), roles);
    }
}
