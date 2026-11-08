package com.ticketya.gestionlugares.reservas.http;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ticketya.gestionlugares.reservas.EstadoVentaReservas;
import com.ticketya.gestionlugares.reservas.ReservasClient;
import com.ticketya.gestionlugares.reservas.ReservasClientException;
import com.ticketya.gestionlugares.reservas.dto.InicializarDisponibilidadRequest;

@Component
public class HttpReservasClient implements ReservasClient {

    private final RestClient restClient;

    public HttpReservasClient(@Qualifier("reservasRestClient") RestClient reservasRestClient) {
        this.restClient = reservasRestClient;
    }

    @Override
    public void inicializarDisponibilidad(String eventoId, InicializarDisponibilidadRequest request) {
        ejecutar("inicializar disponibilidad", () -> requireNoContent(restClient.put()
                .uri("/disponibilidad/eventos/{eventoId}", eventoId)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (httpRequest, response) -> {
                    throw new ReservasClientException("Reservas respondió " + response.getStatusCode()
                            + " al inicializar disponibilidad");
                })
                .toBodilessEntity(), "inicializar disponibilidad"));
    }

    @Override
    public void cambiarEstadoVenta(String eventoId, EstadoVentaReservas estado) {
        ejecutar("cambiar estado de venta", () -> requireNoContent(restClient.put()
                .uri("/disponibilidad/eventos/{eventoId}/venta", eventoId)
                .body(new EstadoVentaRequest(estado))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (httpRequest, response) -> {
                    throw new ReservasClientException("Reservas respondió " + response.getStatusCode()
                            + " al cambiar el estado de venta");
                })
                .toBodilessEntity(), "cambiar el estado de venta"));
    }

    private static Object requireNoContent(ResponseEntity<Void> response, String operacion) {
        if (response.getStatusCode().value() != 204) {
            throw new ReservasClientException("Reservas debe responder 204 para " + operacion
                    + "; respondió " + response.getStatusCode());
        }
        return response;
    }

    private static void ejecutar(String operacion, RunnableHttpCall call) {
        try {
            call.run();
        } catch (ReservasClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new ReservasClientException("No se pudo " + operacion + " en Reservas", exception);
        }
    }

    private record EstadoVentaRequest(EstadoVentaReservas estado) { }

    @FunctionalInterface
    private interface RunnableHttpCall {
        Object run();
    }
}
