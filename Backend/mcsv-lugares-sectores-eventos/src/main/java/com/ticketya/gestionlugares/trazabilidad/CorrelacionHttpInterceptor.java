package com.ticketya.gestionlugares.trazabilidad;

import java.io.IOException;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

public class CorrelacionHttpInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        try (Correlacion.Contexto contexto = Correlacion.usar(Correlacion.actual())) {
            request.getHeaders().set(Correlacion.HEADER, Correlacion.actual());
            return execution.execute(request, body);
        }
    }
}
