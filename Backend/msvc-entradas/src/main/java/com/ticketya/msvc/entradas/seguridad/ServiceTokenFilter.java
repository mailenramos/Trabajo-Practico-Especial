package com.ticketya.msvc.entradas.seguridad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketya.msvc.entradas.config.EntradasProperties;
import com.ticketya.msvc.entradas.dto.ErrorResponseDTO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Protege /internal/**: exige X-Service-Token igual a SERVICE_TOKEN. Si SERVICE_TOKEN no está
 * configurado, la validación queda desactivada (el grupo todavía no definió si todos los servicios
 * lo validan) y se avisa al arrancar. El token nunca se escribe en el log.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ServiceTokenFilter extends OncePerRequestFilter {

    public static final String ENCABEZADO = "X-Service-Token";
    private static final Logger LOG = LoggerFactory.getLogger(ServiceTokenFilter.class);

    private final byte[] token;
    private final ObjectMapper objectMapper;

    public ServiceTokenFilter(EntradasProperties propiedades, ObjectMapper objectMapper) {
        String configurado = propiedades.seguridad().serviceToken();
        this.token = configurado == null || configurado.isBlank()
                ? null
                : configurado.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
        if (token == null) {
            LOG.warn("SERVICE_TOKEN no está configurado: las rutas /internal/** no validan X-Service-Token");
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
        if (recibido == null
                || !MessageDigest.isEqual(token, recibido.getBytes(StandardCharsets.UTF_8))) {
            LOG.warn("Pedido interno rechazado: falta o es incorrecto {} ({} {})",
                    ENCABEZADO, request.getMethod(), request.getRequestURI());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getOutputStream(), new ErrorResponseDTO(
                    Instant.now(), HttpStatus.UNAUTHORIZED.value(), "SERVICIO_NO_AUTENTICADO",
                    "Falta o es incorrecta la credencial de servicio " + ENCABEZADO, null));
            return;
        }
        chain.doFilter(request, response);
    }
}
