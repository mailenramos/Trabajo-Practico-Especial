package ticketya.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Guarda en el MDC el {@value #ENCABEZADO} del pedido entrante (el que agrega el Gateway o reenvía
 * el servicio que llama) mientras dura el pedido; si no viene, o no es válido, genera uno. Así los
 * logs de un pedido quedan unidos. Al terminar el pedido se borra, así no pasa a otro pedido
 * atendido por el mismo hilo. Misma clave de MDC que en Compras.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelacionFilter extends OncePerRequestFilter {

    public static final String ENCABEZADO = "X-Correlation-Id";
    public static final String CLAVE_MDC = "correlacionId";

    /** Solo valores cortos y sin caracteres de control: el valor termina en los logs. */
    private static final Pattern VALIDO = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain cadena)
            throws ServletException, IOException {
        String recibido = request.getHeader(ENCABEZADO);
        String correlacion = recibido != null && VALIDO.matcher(recibido.trim()).matches()
                ? recibido.trim() : UUID.randomUUID().toString();
        MDC.put(CLAVE_MDC, correlacion);
        try {
            cadena.doFilter(request, response);
        } finally {
            MDC.remove(CLAVE_MDC);
        }
    }
}
