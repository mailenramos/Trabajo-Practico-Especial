package com.ticketya.gestionlugares.trazabilidad;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelacionFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelacionFilter.class);
    private static final String ESTADO = CorrelacionFilter.class.getName() + ".estado";

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Estado estado = (Estado) request.getAttribute(ESTADO);
        if (estado == null) {
            estado = new Estado(Correlacion.validarOGenerar(request.getHeader(Correlacion.HEADER)),
                    System.nanoTime(), request.getMethod(), request.getRequestURI(),
                    request.getRequestURI().substring(request.getContextPath().length()), request.getHeader("X-User-Id"));
            request.setAttribute(ESTADO, estado);
        }
        response.setHeader(Correlacion.HEADER, estado.id());
        MDC.put(Correlacion.MDC_KEY, estado.id());
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            registrar(estado, response.getStatus() >= 400 ? response.getStatus() : 500);
            throw exception;
        } finally {
            try {
                if (request.isAsyncStarted()) {
                    if (estado.listenerRegistrado().compareAndSet(false, true)) {
                        request.getAsyncContext().addListener(new RegistroAsync(estado, response));
                    }
                } else {
                    registrar(estado, response.getStatus());
                }
            } finally {
                MDC.remove(Correlacion.MDC_KEY);
            }
        }
    }

    private static void registrar(Estado estado, int status) {
        if (!estado.registrado().compareAndSet(false, true)) return;
        String path = estado.path();
        if (estado.rutaAplicacion().equals("/actuator") || estado.rutaAplicacion().startsWith("/actuator/")) return;
        try (Correlacion.Contexto contexto = Correlacion.usar(estado.id())) {
            long duracion = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - estado.inicio());
            if (estado.usuario() == null || estado.usuario().isBlank()) {
                LOGGER.info("HTTP metodo={} ruta={} estado={} duracionMs={}",
                        seguro(estado.metodo()), seguro(path), status, duracion);
            } else {
                LOGGER.info("HTTP metodo={} ruta={} usuario={} estado={} duracionMs={}",
                        seguro(estado.metodo()), seguro(path), seguro(estado.usuario()), status, duracion);
            }
        }
    }

    private static String seguro(String valor) {
        return valor.replaceAll("[\\p{Cntrl}]", "_");
    }

    private record Estado(String id, long inicio, String metodo, String path, String rutaAplicacion, String usuario,
            AtomicBoolean registrado, AtomicBoolean listenerRegistrado) {
        Estado(String id, long inicio, String metodo, String path, String rutaAplicacion, String usuario) {
            this(id, inicio, metodo, path, rutaAplicacion, usuario, new AtomicBoolean(), new AtomicBoolean());
        }
    }

    private static final class RegistroAsync implements AsyncListener {
        private final Estado estado;
        private final HttpServletResponse response;

        RegistroAsync(Estado estado, HttpServletResponse response) {
            this.estado = estado;
            this.response = response;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            registrar(estado, response.getStatus());
        }

        @Override public void onTimeout(AsyncEvent event) { }
        @Override public void onError(AsyncEvent event) { }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }
}
