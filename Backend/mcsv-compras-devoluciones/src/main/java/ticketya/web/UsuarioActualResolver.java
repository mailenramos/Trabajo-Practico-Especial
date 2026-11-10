package ticketya.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import ticketya.error.ApiException;

/** Arma el {@link UsuarioActual} de cada pedido a partir de los encabezados. */
public class UsuarioActualResolver implements HandlerMethodArgumentResolver {

    public static final String ENCABEZADO_USUARIO = "X-User-Id";
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
        String rol = request.getHeader(ENCABEZADO_ROL);
        return new UsuarioActual(id.trim(), rol == null || rol.isBlank() ? null : rol.trim());
    }
}
