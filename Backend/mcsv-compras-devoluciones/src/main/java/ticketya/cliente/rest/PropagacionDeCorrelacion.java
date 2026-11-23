package ticketya.cliente.rest;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import ticketya.web.CorrelacionFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Agrega {@code X-Correlation-Id} a toda llamada a otro servicio. Lo registra {@link ClienteRest}
 * en todos los clientes, así ninguno puede olvidarlo.
 * <p>
 * El valor es el del MDC ({@value CorrelacionFilter#CLAVE_MDC}), que {@link CorrelacionFilter}
 * carga con el del pedido entrante (el del Gateway). Si no hay (tareas programadas, otro hilo),
 * se genera uno nuevo por llamada. Si quien llama ya puso el encabezado, se respeta.
 */
final class PropagacionDeCorrelacion implements ClientHttpRequestInterceptor {

    static final PropagacionDeCorrelacion INSTANCIA = new PropagacionDeCorrelacion();

    private PropagacionDeCorrelacion() {
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest pedido, byte[] cuerpo, ClientHttpRequestExecution ejecucion)
            throws IOException {
        if (!pedido.getHeaders().containsHeader(CorrelacionFilter.ENCABEZADO)) {
            String correlacion = MDC.get(CorrelacionFilter.CLAVE_MDC);
            pedido.getHeaders().set(CorrelacionFilter.ENCABEZADO,
                    correlacion != null && !correlacion.isBlank() ? correlacion : UUID.randomUUID().toString());
        }
        return ejecucion.execute(pedido, cuerpo);
    }
}
