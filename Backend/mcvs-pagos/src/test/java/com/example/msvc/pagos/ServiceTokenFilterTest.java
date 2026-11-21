package com.example.msvc.pagos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServiceTokenFilterTest {

    private static MockHttpServletResponse call(ServiceTokenFilter filter, String path, String token,
                                                MockFilterChain chain) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (token != null) {
            request.addHeader(ServiceTokenFilter.HEADER, token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void disabledLetsInternalCallsThroughWithoutToken() throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = call(new ServiceTokenFilter("secreto", false), "/internal/pagos", null, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void enabledAcceptsMatchingToken() throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = call(new ServiceTokenFilter("secreto", true), "/internal/pagos", "secreto", chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void enabledRejectsMissingOrWrongToken() throws ServletException, IOException {
        ServiceTokenFilter filter = new ServiceTokenFilter("secreto", true);

        MockFilterChain sinToken = new MockFilterChain();
        MockHttpServletResponse response = call(filter, "/internal/pagos", null, sinToken);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("SERVICIO_NO_AUTORIZADO"));
        assertNull(sinToken.getRequest());

        MockFilterChain otroToken = new MockFilterChain();
        assertEquals(403, call(filter, "/internal/pagos", "otro", otroToken).getStatus());
        assertNull(otroToken.getRequest());
    }

    @Test
    void enabledWithoutConfiguredTokenFailsClosed() throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(403, call(new ServiceTokenFilter("", true), "/internal/pagos", "", chain).getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void healthIsNeverFiltered() throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = call(new ServiceTokenFilter("secreto", true), "/actuator/health", null, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }
}
