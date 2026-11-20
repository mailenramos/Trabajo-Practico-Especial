package ar.edu.unicen.ms_disponibilidad_reservas.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.function.Function;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

final class ClienteHttpInterno {
    private final RestClient client;
    private final String servicio;
    private final CircuitBreaker circuito;
    private final Retry reintento;

    ClienteHttpInterno(String baseUrl, long conexionMs, long lecturaMs, String servicio) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(conexionMs)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(lecturaMs));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.servicio = servicio;
        var config = CircuitBreakerConfig.custom().slidingWindowSize(10).minimumNumberOfCalls(5)
                .failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(30))
                .ignoreException(e -> e instanceof RespuestaHttpClienteException || !(e instanceof IntegracionException ex && (ex.tipo() == IntegracionException.Tipo.NO_DISPONIBLE || ex.tipo() == IntegracionException.Tipo.RESPUESTA_INVALIDA)))
                .build();
        this.circuito = CircuitBreaker.of(servicio, config);
        this.reintento = Retry.of(servicio + "-get", RetryConfig.custom().maxAttempts(2)
                .waitDuration(Duration.ofMillis(100))
                .retryOnException(e -> e instanceof IntegracionException ex && ex.tipo() == IntegracionException.Tipo.NO_DISPONIBLE).build());
    }

    <T> T get(String path, String eventoId, String correlationId, Function<String, T> validar) {
        return protegido(() -> validar.apply(Retry.decorateSupplier(reintento,
                () -> ejecutar(client.get().uri(path, eventoId), correlationId)).get()));
    }

    <T> T post(String path, String eventoId, Object body, String correlationId, Function<String, T> validar) {
        return protegido(() -> validar.apply(ejecutar(client.post().uri(path, eventoId).contentType(MediaType.APPLICATION_JSON).body(body), correlationId)));
    }

    private <T> T protegido(Supplier<T> llamada) {
        try { return circuito.executeSupplier(llamada); }
        catch (CallNotPermittedException ex) {
            throw new IntegracionException(IntegracionException.Tipo.NO_DISPONIBLE, "No fue posible comunicarse con " + servicio + ".");
        }
    }

    private String ejecutar(RestClient.RequestHeadersSpec<?> request, String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) request.header("X-Correlation-Id", correlationId);
        try {
            return request.accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status >= 400 && status < 500 && status != 404 && status != 409) {
                throw new RespuestaHttpClienteException("No fue posible completar la consulta a " + servicio + ".");
            }
            var tipo = switch (status) {
                case 404 -> IntegracionException.Tipo.NO_ENCONTRADO;
                case 409 -> IntegracionException.Tipo.CONFLICTO;
                default -> status >= 500 ? IntegracionException.Tipo.NO_DISPONIBLE : IntegracionException.Tipo.RESPUESTA_INVALIDA;
            };
            throw new IntegracionException(tipo, "No fue posible completar la consulta a " + servicio + ".");
        } catch (ResourceAccessException e) {
            throw new IntegracionException(IntegracionException.Tipo.NO_DISPONIBLE, "No fue posible comunicarse con " + servicio + ".");
        } catch (RestClientException e) {
            throw new IntegracionException(IntegracionException.Tipo.RESPUESTA_INVALIDA, "Respuesta inválida de " + servicio + ".");
        }
    }

    private static final class RespuestaHttpClienteException extends IntegracionException {
        private RespuestaHttpClienteException(String mensaje) { super(Tipo.RESPUESTA_INVALIDA, mensaje); }
    }
}
