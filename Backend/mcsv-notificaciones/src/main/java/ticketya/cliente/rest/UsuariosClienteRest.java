package ticketya.cliente.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ticketya.cliente.UsuariosCliente;
import ticketya.envio.FalloDefinitivoException;
import ticketya.envio.FalloTemporalException;

import java.time.Duration;

/**
 * Contrato acordado: GET /internal/usuarios/{id}/contacto en MS6. La forma del cuerpo es
 * contrato a acordar; se asume {@code {"correo": "..."}} e ignora los demás campos.
 * <p>
 * Timeouts de conexión y lectura, y circuit breaker: con el circuito abierto no se llama a MS6
 * y la notificación se pospone como fallo temporal.
 */
public class UsuariosClienteRest implements UsuariosCliente {

    static final String RUTA = "/internal/usuarios/{id}/contacto";

    private final RestClient http;
    private final CircuitBreaker circuito;

    public UsuariosClienteRest(String urlBase, Duration timeoutConexion, Duration timeoutLectura,
                               String tokenServicio, CircuitBreaker circuito) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(timeoutConexion);
        fabrica.setReadTimeout(timeoutLectura);
        RestClient.Builder builder = RestClient.builder().baseUrl(urlBase).requestFactory(fabrica);
        if (tokenServicio != null && !tokenServicio.isBlank()) {
            // Mismo mecanismo provisorio de autorización entre servicios que usa este servicio
            builder.defaultHeader("X-Service-Token", tokenServicio);
        }
        this.http = builder.build();
        this.circuito = circuito;
    }

    @Override
    public String correoDe(String usuarioId) {
        Contacto contacto;
        try {
            contacto = circuito.executeSupplier(() -> pedirContacto(usuarioId));
        } catch (CallNotPermittedException e) {
            throw new FalloTemporalException("Circuit breaker hacia Gestión de usuarios abierto", e);
        }
        if (contacto == null || contacto.correo() == null || contacto.correo().isBlank()) {
            throw new FalloDefinitivoException("El usuario " + usuarioId + " no tiene correo registrado");
        }
        return contacto.correo().trim();
    }

    /**
     * Solo los fallos temporales cuentan para el circuit breaker (ver la configuración del
     * circuito); un 404 es una respuesta válida de MS6.
     */
    private Contacto pedirContacto(String usuarioId) {
        try {
            return http.get().uri(RUTA, usuarioId).retrieve().body(Contacto.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw new FalloDefinitivoException("El usuario " + usuarioId + " no existe en Gestión de usuarios");
            }
            throw new FalloTemporalException("Gestión de usuarios respondió " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new FalloTemporalException("Sin respuesta de Gestión de usuarios: " + e.getMessage(), e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Contacto(String correo) {
    }
}
