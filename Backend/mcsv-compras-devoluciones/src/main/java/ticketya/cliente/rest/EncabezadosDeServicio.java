package ticketya.cliente.rest;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import ticketya.web.CorrelacionFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Agrega a cada llamada a otro servicio los encabezados de identificación:
 * <ul>
 *   <li>{@code X-Service-Token} con el token compartido (SERVICIO_TOKEN). Sin token configurado
 *       no se envía: el otro servicio responde 403, que se trata como una falla técnica.</li>
 *   <li>{@code X-Correlation-Id}: el del pedido en curso (el del Gateway) o, si la llamada nace de
 *       una tarea programada o de otro hilo, uno nuevo.</li>
 * </ul>
 */
class EncabezadosDeServicio implements ClientHttpRequestInterceptor {

    static final String ENCABEZADO_TOKEN = "X-Service-Token";

    private final String token;

    EncabezadosDeServicio(String token) {
        this.token = token == null || token.isBlank() ? null : token;
    }

    boolean tokenConfigurado() {
        return token != null;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest pedido, byte[] cuerpo, ClientHttpRequestExecution ejecucion)
            throws IOException {
        if (token != null) {
            pedido.getHeaders().set(ENCABEZADO_TOKEN, token);
        }
        String correlacion = CorrelacionFilter.actual();
        pedido.getHeaders().set(CorrelacionFilter.ENCABEZADO,
                correlacion != null ? correlacion : UUID.randomUUID().toString());
        return ejecucion.execute(pedido, cuerpo);
    }
}
