package ticketya.cliente.rest;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Agrega a cada llamada a otro servicio {@code X-Service-Token} con el token compartido
 * (SERVICIO_TOKEN). Sin token configurado no se envía: el otro servicio responde 403, que se trata
 * como una falla técnica. ({@code X-Correlation-Id} lo agrega {@link PropagacionDeCorrelacion} en
 * todos los clientes.)
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
        return ejecucion.execute(pedido, cuerpo);
    }
}
