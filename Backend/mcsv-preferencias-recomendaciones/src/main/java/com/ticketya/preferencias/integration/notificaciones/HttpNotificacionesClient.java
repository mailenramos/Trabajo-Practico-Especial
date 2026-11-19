package com.ticketya.preferencias.integration.notificaciones;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ticketya.preferencias.api.AvisoTokenInterceptor;
import com.ticketya.preferencias.integration.compras.ComprasProperties;

@Component
public class HttpNotificacionesClient implements NotificacionesClient {
    private final RestClient restClient;
    private final ComprasProperties comprasProperties;

    public HttpNotificacionesClient(@Qualifier("notificacionesRestClient") RestClient restClient,
            ComprasProperties comprasProperties) {
        this.restClient = restClient;
        this.comprasProperties = comprasProperties;
    }

    @Override
    public void enviar(NotificacionRequest notificacion) {
        try {
            var status = restClient.post()
                    .uri("/internal/notificaciones")
                    .header(AvisoTokenInterceptor.SERVICE_TOKEN_HEADER, comprasProperties.getServicioToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(notificacion)
                    .exchange((request, response) -> response.getStatusCode());
            if (status.value() != 202) {
                throw new NotificacionesUnavailableException(
                        "Notificaciones respondió HTTP " + status.value());
            }
        } catch (RestClientException exception) {
            throw new NotificacionesUnavailableException("No se pudo entregar el aviso a Notificaciones", exception);
        }
    }
}
