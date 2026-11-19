package com.example.msvc.pagos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void preservesValidRequestIdAndReturnsItInResponse() throws ServletException, IOException {
        String requestId = "45feec1c-77bb-4a01-9a10-0d19063a7772";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/pagos");
        request.addHeader("X-Request-Id", requestId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(requestId, response.getHeader("X-Request-Id"));
    }

    @Test
    void replacesInvalidRequestId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/pagos");
        request.addHeader("X-Request-Id", "not-a-uuid\nforged-log-entry");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String requestId = response.getHeader("X-Request-Id");
        org.junit.jupiter.api.Assertions.assertNotNull(requestId);
        assertEquals(36, requestId.length());
    }
}
