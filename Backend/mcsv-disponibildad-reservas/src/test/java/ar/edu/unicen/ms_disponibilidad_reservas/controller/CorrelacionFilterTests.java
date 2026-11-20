package ar.edu.unicen.ms_disponibilidad_reservas.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.ServletException;

class CorrelacionFilterTests {
    @Test
    void generaIdSeguroYLimpiaMdcAnteUnaExcepcion() {
        var filter = new CorrelacionFilter();
        var request = new MockHttpServletRequest("GET", "/ruta");
        request.addHeader("X-Correlation-Id", "id con espacios");
        var response = new MockHttpServletResponse();
        assertThrows(ServletException.class, () -> filter.doFilter(request, response, (req, res) -> {
            String id = MDC.get(CorrelacionFilter.CORRELACION);
            assertEquals(id, response.getHeader("X-Correlation-Id"));
            assertNotNull(UUID.fromString(id));
            MDC.put(CorrelacionFilter.USUARIO, "usuario");
            throw new ServletException("fallo de prueba");
        }));
        assertNull(MDC.get(CorrelacionFilter.CORRELACION));
        assertNull(MDC.get(CorrelacionFilter.USUARIO));
    }
}
