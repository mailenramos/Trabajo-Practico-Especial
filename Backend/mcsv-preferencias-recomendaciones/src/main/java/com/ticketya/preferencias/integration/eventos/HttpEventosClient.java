package com.ticketya.preferencias.integration.eventos;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpEventosClient implements EventosClient {
    private static final int PAGE_SIZE = 200;
    private static final String CATALOG_PATH = "/internal/eventos-publicados";

    private final RestClient restClient;

    public HttpEventosClient(@Qualifier("eventosRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<EventoPublicado> listarTodos() {
        List<EventoPublicado> eventos = new ArrayList<>();
        int pagina = 0;
        while (true) {
            EventosPublicadosPage respuesta = obtenerPagina(pagina);
            if (respuesta == null || respuesta.contenido() == null
                    || respuesta.pagina() != pagina || respuesta.totalPaginas() < 0
                    || (respuesta.totalPaginas() == 0 && !respuesta.contenido().isEmpty())) {
                throw new EventosCatalogoUnavailableException("MS1 devolvió una página inválida");
            }

            eventos.addAll(respuesta.contenido());
            if (pagina + 1 >= respuesta.totalPaginas()) {
                return List.copyOf(eventos);
            }
            pagina++;
        }
    }

    private EventosPublicadosPage obtenerPagina(int pagina) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(CATALOG_PATH)
                            .queryParam("page", pagina)
                            .queryParam("size", PAGE_SIZE)
                            .build())
                    .retrieve()
                    .body(EventosPublicadosPage.class);
        } catch (RestClientException exception) {
            throw new EventosCatalogoUnavailableException("No se pudo consultar el catálogo de MS1", exception);
        }
    }
}
