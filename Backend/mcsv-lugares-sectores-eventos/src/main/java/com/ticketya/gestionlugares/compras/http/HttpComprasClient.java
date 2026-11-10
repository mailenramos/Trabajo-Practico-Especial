package com.ticketya.gestionlugares.compras.http;

import java.time.Instant;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ticketya.gestionlugares.compras.ComprasClient;
import com.ticketya.gestionlugares.compras.ComprasClientException;
import com.ticketya.gestionlugares.compras.EventoCanceladoRequest;

@Component
public class HttpComprasClient implements ComprasClient {

    private final RestClient restClient;

    public HttpComprasClient(@Qualifier("comprasRestClient") RestClient comprasRestClient) {
        this.restClient = comprasRestClient;
    }

    @Override
    public void notificarCancelacion(String eventoId, String motivo, Instant canceladoEn) {
        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri("/eventos-cancelados")
                    .body(new EventoCanceladoRequest(eventoId, motivo, canceladoEn.toString()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, result) -> {
                        throw new ComprasClientException("Compras respondió " + result.getStatusCode()
                                + " al recibir el aviso de cancelación");
                    })
                    .toBodilessEntity();
            int status = response.getStatusCode().value();
            if (status != 200 && status != 202) {
                throw new ComprasClientException("Compras debe responder 200 o 202 al aviso; respondió "
                        + response.getStatusCode());
            }
        } catch (ComprasClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new ComprasClientException("No se pudo enviar a Compras el aviso de cancelación", exception);
        }
    }
}
