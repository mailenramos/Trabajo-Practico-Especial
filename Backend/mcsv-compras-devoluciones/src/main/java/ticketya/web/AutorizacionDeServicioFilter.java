package ticketya.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ticketya.error.ErrorRespuesta;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Solo los servicios autorizados pueden llamar a {@code /internal/**}: deben enviar el encabezado
 * {@value #ENCABEZADO} con el token compartido (variable de entorno SERVICIO_TOKEN). Mecanismo
 * provisorio de la Entrega 2, el mismo que usa Notificaciones, hasta la adaptación a JWT.
 * <p>
 * Falla cerrado: sin token configurado rechaza todas las llamadas internas. La comparación es de
 * tiempo constante (se comparan los SHA-256 de ambos valores, así tampoco se filtra el largo).
 * {@code /eventos-cancelados} no está bajo {@code /internal/} y queda como estaba.
 */
@Component
public class AutorizacionDeServicioFilter extends OncePerRequestFilter {

    public static final String ENCABEZADO = "X-Service-Token";
    static final String PREFIJO_INTERNO = "/internal/";

    private static final Logger log = LoggerFactory.getLogger(AutorizacionDeServicioFilter.class);

    private final byte[] resumenEsperado;
    private final JsonMapper json;

    public AutorizacionDeServicioFilter(@Value("${compras.seguridad.token-servicio:}") String tokenServicio,
                                        JsonMapper json) {
        this.json = json;
        if (tokenServicio != null && !tokenServicio.isBlank()) {
            this.resumenEsperado = resumen(tokenServicio);
        } else {
            this.resumenEsperado = null;
            log.warn("SERVICIO_TOKEN no está configurado: se rechazarán todas las llamadas a {}**", PREFIJO_INTERNO);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + PREFIJO_INTERNO);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain cadena)
            throws ServletException, IOException {
        if (autorizado(request.getHeader(ENCABEZADO))) {
            cadena.doFilter(request, response);
            return;
        }
        log.warn("Llamada no autorizada a {} {} desde {}", request.getMethod(), request.getRequestURI(),
                request.getRemoteAddr());
        int prohibido = HttpStatus.FORBIDDEN.value();
        response.setStatus(prohibido);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), ErrorRespuesta.de(prohibido, "SERVICIO_NO_AUTORIZADO",
                "Solo los servicios autorizados pueden llamar a este endpoint"));
    }

    boolean autorizado(String tokenRecibido) {
        if (resumenEsperado == null || tokenRecibido == null) {
            return false;
        }
        return MessageDigest.isEqual(resumenEsperado, resumen(tokenRecibido));
    }

    private static byte[] resumen(String valor) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
