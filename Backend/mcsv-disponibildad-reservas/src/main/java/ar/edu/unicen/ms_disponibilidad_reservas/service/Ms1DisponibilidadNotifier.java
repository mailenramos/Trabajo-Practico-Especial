package ar.edu.unicen.ms_disponibilidad_reservas.service;

import org.springframework.beans.factory.annotation.Value;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Direct internal REST client for MS1's availability-state endpoint. */
@Component
public class Ms1DisponibilidadNotifier {

    private final RestClient restClient;

    public Ms1DisponibilidadNotifier(
            @Value("${ms1.api.base-url:http://localhost:8081}") String baseUrl,
            @Value("${ms2.outbox.connect-timeout-ms:2000}") long connectTimeoutMillis,
            @Value("${ms2.outbox.read-timeout-ms:5000}") long readTimeoutMillis) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public int notificar(String eventoId, boolean agotado) {
        return restClient.put()
                .uri("/eventos/{eventoId}/disponibilidad", eventoId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EstadoDisponibilidadRequest(agotado))
                .exchange((request, response) -> response.getStatusCode().value());
    }

    public record EstadoDisponibilidadRequest(boolean agotado) {
    }
}
