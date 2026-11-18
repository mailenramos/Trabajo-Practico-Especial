package com.ticketya.gestionlugares.outbox;

import java.net.URI;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.ticketya.gestionlugares.domain.AvisoOutbox;

@Component
public class RestClientOutboxHttpClient implements OutboxHttpClient {

    private final RestClient restClient;

    public RestClientOutboxHttpClient(@Qualifier("outboxRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public int enviar(AvisoOutbox aviso) {
        return restClient.post()
                .uri(URI.create(aviso.getDestino()))
                .header("X-Aviso-Id", aviso.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(aviso.getPayload())
                .exchange((request, response) -> response.getStatusCode().value());
    }
}
