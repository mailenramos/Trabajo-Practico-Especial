package com.example.msvc.pagos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Valida el header {@value #HEADER} (token compartido SERVICIO_TOKEN) en {@code /internal/**}.
 * <p>
 * Solo actúa con {@code pagos.seguridad.exigir-token-servicio=true}; así no se corta el flujo
 * de cobro mientras Compras no envíe el header a Pagos. Activo y sin token configurado, falla
 * cerrado (rechaza todo). La comparación es de tiempo constante sobre los SHA-256.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ServiceTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Service-Token";
    private static final String INTERNAL_PREFIX = "/internal/";
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceTokenFilter.class);

    private final boolean enforced;
    private final byte[] expectedDigest;

    public ServiceTokenFilter(
            @Value("${pagos.seguridad.token-servicio:}") String serviceToken,
            @Value("${pagos.seguridad.exigir-token-servicio:false}") boolean enforced) {
        this.enforced = enforced;
        this.expectedDigest = serviceToken == null || serviceToken.isBlank() ? null : digest(serviceToken);
        if (enforced && expectedDigest == null) {
            LOGGER.warn("SERVICIO_TOKEN no está configurado: se rechazarán todas las llamadas a {}**", INTERNAL_PREFIX);
        } else if (!enforced) {
            LOGGER.info("Validación de {} desactivada (PAGOS_EXIGIR_TOKEN_SERVICIO=false)", HEADER);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enforced || !request.getRequestURI().startsWith(request.getContextPath() + INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (authorized(request.getHeader(HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        LOGGER.warn("Llamada no autorizada a {} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":403,"
                + "\"error\":\"SERVICIO_NO_AUTORIZADO\","
                + "\"mensaje\":\"Solo los servicios autorizados pueden llamar a este endpoint\"}");
    }

    boolean authorized(String receivedToken) {
        if (expectedDigest == null || receivedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedDigest, digest(receivedToken));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
