package ar.edu.unicen.ms_disponibilidad_reservas.controller;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServiceTokenFilterTests {
    @Test
    void protegeSoloRutasInternas() throws Exception {
        var filter = new ServiceTokenFilter("prueba");
        for (String token : new String[] {"", "incorrecto", "prueba"}) {
            var request = new MockHttpServletRequest("GET", "/internal/prueba");
            if (!token.isEmpty()) request.addHeader("X-Service-Token", token);
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertEquals(token.equals("prueba") ? 200 : 403, response.getStatus());
            assertEquals(token.equals("prueba"), chain.getRequest() != null);
        }
        for (String path : new String[] {"/actuator/health", "/publico", "/internalizado"}) {
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(new MockHttpServletRequest("GET", path), response, chain);
            assertNotNull(chain.getRequest());
        }
        assertThrows(IllegalArgumentException.class, () -> new ServiceTokenFilter(" "));
    }
}
