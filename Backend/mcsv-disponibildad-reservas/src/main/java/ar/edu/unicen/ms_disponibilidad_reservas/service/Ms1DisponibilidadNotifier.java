package ar.edu.unicen.ms_disponibilidad_reservas.service;

import org.springframework.beans.factory.annotation.Value;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;

/** Direct internal REST client for MS1's availability-state endpoint. */
@Component
public class Ms1DisponibilidadNotifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(Ms1DisponibilidadNotifier.class);

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
        long inicio = System.nanoTime();
        try {
            int status = restClient.put()
                .uri("/eventos/{eventoId}/disponibilidad", eventoId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EstadoDisponibilidadRequest(agotado))
                .exchange((request, response) -> response.getStatusCode().value());
            LOGGER.info("notificacion_asincrona destino=MS1 metodo=PUT operacion=disponibilidad estado={} duracion_ms={}",
                    status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio));
            return status;
        } catch (RuntimeException e) {
            LOGGER.warn("notificacion_asincrona destino=MS1 metodo=PUT operacion=disponibilidad error={} duracion_ms={}",
                    e.getClass().getSimpleName(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio));
            throw e;
        }
    }

    public record EstadoDisponibilidadRequest(boolean agotado) {
    }
}
