package com.ticketya.gestionlugares.outbox;

import java.net.URI;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.ticketya.gestionlugares.config.ServicioToken;
import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.trazabilidad.Correlacion;

@Component
public class RestClientOutboxHttpClient implements OutboxHttpClient {

    private final RestClient restClient;
    private final RestClient reservasRestClient;
    private final ServicioToken servicioToken;

    public RestClientOutboxHttpClient(@Qualifier("outboxRestClient") RestClient restClient,
            @Qualifier("outboxReservasRestClient") RestClient reservasRestClient,
            ServicioToken servicioToken) {
        this.restClient = restClient;
        this.reservasRestClient = reservasRestClient;
        this.servicioToken = servicioToken;
    }

    @Override
    public int enviar(AvisoOutbox aviso) {
        try (Correlacion.Contexto contexto = Correlacion.usar(
                Correlacion.actual() == null ? aviso.getCorrelationId() : Correlacion.actual())) {
            HttpMethod metodo = aviso.getMetodoHttp() == null
                    ? HttpMethod.POST : HttpMethod.valueOf(aviso.getMetodoHttp());
            RestClient cliente = "CANCELACION_RESERVAS".equals(aviso.getTipo()) ? reservasRestClient : restClient;
            RestClient.RequestBodySpec solicitud = cliente.method(metodo)
                    .uri(URI.create(aviso.getDestino()))
                    .header("X-Aviso-Id", aviso.getId())
                    .header("X-Service-Token", servicioToken.valor())
                    .header(Correlacion.HEADER, Correlacion.actual());
            if (aviso.getPayload() != null) {
                solicitud.contentType(MediaType.APPLICATION_JSON).body(aviso.getPayload());
            }
            return solicitud.exchange((request, response) -> response.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            throw new ResourceAccessException("Error de transporte: "
                    + exception.getMostSpecificCause().getClass().getSimpleName());
        }
    }
}
