package ar.edu.unicen.ms_fila_virtual.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.MDC;
import ar.edu.unicen.ms_fila_virtual.controller.CorrelacionFilter;

@Component
public class Ms1EventoClient {
    private final RestClient http;
    private final CircuitBreaker consulta = circuito("ms1-consulta");
    private final CircuitBreaker politica = circuito("ms1-politica");
    private final Retry reintentoConsulta = Retry.of("ms1-consulta", RetryConfig.custom().maxAttempts(2)
            .waitDuration(Duration.ofMillis(100)).retryOnException(Ms1EventoClient::transitorio).build());

    private static boolean transitorio(Throwable e) {
        Throwable causa = e.getCause();
        return causa instanceof org.springframework.web.client.ResourceAccessException
                || causa instanceof RestClientResponseException response && response.getStatusCode().is5xxServerError();
    }

    private static CircuitBreaker circuito(String nombre) {
        return CircuitBreaker.of(nombre, CircuitBreakerConfig.custom().slidingWindowSize(10).minimumNumberOfCalls(5)
                .failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(30))
                .ignoreException(e -> !(e instanceof Ms1NoDisponibleException) || e.getCause() instanceof RestClientResponseException response && !response.getStatusCode().is5xxServerError())
                .build());
    }

    private <T> T protegido(CircuitBreaker breaker, Supplier<T> llamada) {
        try { return breaker.executeSupplier(llamada); }
        catch (CallNotPermittedException e) { throw new Ms1NoDisponibleException("MS1 no está disponible.", e); }
    }

    @Autowired
    public Ms1EventoClient(@Value("${ms1.api.base-url:http://localhost:8081}") String baseUrl,
            @Value("${ms1.api.connect-timeout-ms:2000}") long connectTimeout,
            @Value("${ms1.api.read-timeout-ms:5000}") long readTimeout, @Value("${SERVICIO_TOKEN}") String token) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout)).build());
        factory.setReadTimeout(Duration.ofMillis(readTimeout));
        http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).defaultHeader("X-Service-Token", token).build();
    }

    Ms1EventoClient(RestClient http) { this.http = http; }

    public Evento consultar(String eventoId) {
        return protegido(consulta, () -> Retry.decorateSupplier(reintentoConsulta, () -> consultarUnaVez(eventoId)).get());
    }

    private Evento consultarUnaVez(String eventoId) {
        try {
            Evento evento = http.get().uri("/internal/eventos/{eventoId}", eventoId)
                    .headers(Ms1EventoClient::propagarCorrelacion).retrieve().body(Evento.class);
            if (evento == null || !eventoId.equals(evento.eventoId()) || evento.estado() == null
                    || !Set.of("borrador", "publicado", "agotado", "cancelado", "finalizado").contains(evento.estado())) {
                throw new Ms1NoDisponibleException("MS1 devolvió datos de evento incompletos o inválidos.");
            }
            return evento;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) throw new FilaNoEncontradaException("No existe el evento en MS1.");
            throw new Ms1NoDisponibleException("No se pudo consultar el evento en MS1.", e);
        } catch (RestClientException e) {
            throw new Ms1NoDisponibleException("No se pudo consultar el evento en MS1.", e);
        }
    }

    public void actualizarPolitica(String eventoId, boolean requiereFila) {
        protegido(politica, () -> { actualizarPoliticaUnaVez(eventoId, requiereFila); return null; });
    }

    private void actualizarPoliticaUnaVez(String eventoId, boolean requiereFila) {
        try {
            HttpStatusCode status = http.put().uri("/internal/eventos/{eventoId}/politica-fila", eventoId)
                    .headers(Ms1EventoClient::propagarCorrelacion)
                    .contentType(MediaType.APPLICATION_JSON).body(new PoliticaFila(requiereFila))
                    .retrieve().toBodilessEntity().getStatusCode();
            if (!status.is2xxSuccessful()) throw new Ms1NoDisponibleException("MS1 no confirmó la política de fila.");
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) throw new FilaConflictException("MS1 rechazó el cambio de política del evento.");
            throw new Ms1NoDisponibleException("MS1 no confirmó la política de fila; puede reintentarse la misma operación.", e);
        } catch (RestClientException e) {
            throw new Ms1NoDisponibleException("MS1 no confirmó la política de fila; puede reintentarse la misma operación.", e);
        }
    }

    private static void propagarCorrelacion(org.springframework.http.HttpHeaders headers) {
        String id = MDC.get(CorrelacionFilter.CORRELACION);
        if (id != null) headers.set("X-Correlation-Id", id);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Evento(String eventoId, String organizadorId, String estado, Boolean requiereFila) { }
    private record PoliticaFila(boolean requiereFila) { }
}
