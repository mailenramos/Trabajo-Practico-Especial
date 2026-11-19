package com.ticketya.preferencias.integration.compras;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpComprasClient implements ComprasClient {
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final int MAX_PURCHASED_EVENTS = 20;
    private static final int MAX_PURCHASED_EVENTS_FOR_COPURCHASE = 5;

    private final RestClient restClient;
    private final String servicioToken;

    public HttpComprasClient(@Qualifier("comprasRestClient") RestClient restClient,
            ComprasProperties properties) {
        this.restClient = restClient;
        this.servicioToken = properties.getServicioToken();
    }

    @Override
    public SenalesCompras obtenerSenales(String usuarioId) {
        try {
            HistorialPreferenciasResponse historial = obtenerHistorial(usuarioId);
            if (historial == null || historial.tipos() == null) {
                throw new ComprasUnavailableException("Compras devolvió un historial inválido");
            }

            Set<String> tipos = historial.tipos().stream()
                    .filter(Objects::nonNull)
                    .map(TipoComprado::tipo)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> coCompras = new LinkedHashSet<>();
            for (String eventoId : primerosEventosComprados(historial.eventosComprados())) {
                CoComprasResponse respuesta = obtenerCoCompras(eventoId);
                if (respuesta == null || respuesta.coCompras() == null) {
                    throw new ComprasUnavailableException("Compras devolvió co-compras inválidas");
                }
                respuesta.coCompras().stream()
                        .filter(Objects::nonNull)
                        .map(EventoCoCompra::eventoId)
                        .filter(Objects::nonNull)
                        .forEach(coCompras::add);
            }
            return new SenalesCompras(tipos, coCompras);
        } catch (RestClientException exception) {
            throw new ComprasUnavailableException("No se pudieron consultar señales de Compras", exception);
        }
    }

    private HistorialPreferenciasResponse obtenerHistorial(String usuarioId) {
        return restClient.get()
                .uri("/internal/usuarios/{usuarioId}/historial-preferencias", usuarioId)
                .header(SERVICE_TOKEN_HEADER, servicioToken)
                .retrieve()
                .body(HistorialPreferenciasResponse.class);
    }

    private CoComprasResponse obtenerCoCompras(String eventoId) {
        return restClient.get()
                .uri("/internal/eventos/{eventoId}/co-compras", eventoId)
                .header(SERVICE_TOKEN_HEADER, servicioToken)
                .retrieve()
                .body(CoComprasResponse.class);
    }

    private List<String> primerosEventosComprados(List<String> eventosComprados) {
        if (eventosComprados == null) {
            return List.of();
        }
        return eventosComprados.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(eventoId -> !eventoId.isEmpty())
                .distinct()
                .limit(MAX_PURCHASED_EVENTS)
                .limit(MAX_PURCHASED_EVENTS_FOR_COPURCHASE)
                .toList();
    }
}
