package com.ticketya.usuarios.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketya.usuarios.error.ErrorRespuesta;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Protege /internal/**: exige X-Service-Token igual a SERVICIO_TOKEN (acuerdo del grupo). Si
 * SERVICIO_TOKEN no está configurado, la validación queda desactivada y se avisa al arrancar
 * (solo desarrollo local; en Docker es obligatorio). El token nunca se escribe en el log.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ServicioTokenFilter extends OncePerRequestFilter {

    public static final String ENCABEZADO = "X-Service-Token";
    private static final Logger LOG = LoggerFactory.getLogger(ServicioTokenFilter.class);

    private final byte[] token;
    private final ObjectMapper objectMapper;

    public ServicioTokenFilter(@Value("${usuarios.seguridad.servicio-token:}") String configurado,
                               ObjectMapper objectMapper) {
        this.token = configurado == null || configurado.isBlank()
                ? null
                : configurado.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
        if (token == null) {
            LOG.warn("SERVICIO_TOKEN no está configurado: las rutas /internal/** no validan X-Service-Token");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return token == null || !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String recibido = request.getHeader(ENCABEZADO);
        if (recibido == null || !MessageDigest.isEqual(token, recibido.getBytes(StandardCharsets.UTF_8))) {
            LOG.warn("Pedido interno rechazado: falta o es incorrecto {} ({} {})",
                    ENCABEZADO, request.getMethod(), request.getRequestURI());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getOutputStream(), new ErrorRespuesta(
                    Instant.now(), HttpStatus.UNAUTHORIZED.value(), "SERVICIO_NO_AUTENTICADO",
                    "Falta o es incorrecta la credencial de servicio " + ENCABEZADO, null));
            return;
        }
        chain.doFilter(request, response);
    }
}
