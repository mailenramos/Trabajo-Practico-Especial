package ticketya.cliente.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import ticketya.cliente.ContratoInvalidoException;
import ticketya.cliente.ServicioExternoException;
import tools.jackson.core.JacksonException;

import java.time.Duration;
import java.util.function.Supplier;

/** Base común de los clientes REST: timeouts y traducción de fallas técnicas. */
abstract class ClienteRest {

    protected final RestClient http;
    private final String servicio;

    protected ClienteRest(String servicio, String urlBase, Duration timeoutConexion, Duration timeoutLectura) {
        this.servicio = servicio;
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(timeoutConexion);
        fabrica.setReadTimeout(timeoutLectura);
        this.http = RestClient.builder().baseUrl(urlBase).requestFactory(fabrica).build();
    }

    /**
     * Ejecuta la llamada. Las respuestas de error HTTP las decide quien llama mediante
     * {@code alError}; si devuelve null, o si no hubo respuesta, se lanza
     * {@link ServicioExternoException}.
     */
    protected <T> T llamar(String operacion, Supplier<T> llamada, ManejoDeError<T> alError) {
        try {
            return llamada.get();
        } catch (RestClientResponseException e) {
            T manejado = alError.manejar(e.getStatusCode(), e);
            if (manejado != null) {
                return manejado;
            }
            throw new ServicioExternoException(servicio,
                    servicio + " respondió " + e.getStatusCode().value() + " en " + operacion, e);
        } catch (RestClientException e) {
            if (esCuerpoIlegible(e)) {
                throw new ContratoInvalidoException(servicio,
                        servicio + " respondió datos con un formato inesperado en " + operacion, e);
            }
            throw new ServicioExternoException(servicio, "Sin respuesta de " + servicio + " en " + operacion, e);
        }
    }

    /** La respuesta llegó pero no se pudo leer (por ejemplo, una fila "A" donde va un entero). */
    private static boolean esCuerpoIlegible(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpMessageNotReadableException || t instanceof JacksonException) {
                return true;
            }
        }
        return false;
    }

    protected <T> T llamar(String operacion, Supplier<T> llamada) {
        return llamar(operacion, llamada, (status, e) -> null);
    }

    /**
     * Código de negocio de una respuesta de error: el del campo "error" (formato del grupo)
     * o, si no viene, el de "codigo" (formato anterior de Entradas, mientras migra).
     */
    protected static String codigoDeError(RestClientResponseException e) {
        try {
            CuerpoDeError cuerpo = e.getResponseBodyAs(CuerpoDeError.class);
            if (cuerpo == null) {
                return null;
            }
            if (cuerpo.error() != null && !cuerpo.error().isBlank()) {
                return cuerpo.error();
            }
            return cuerpo.codigo() != null && !cuerpo.codigo().isBlank() ? cuerpo.codigo() : null;
        } catch (RuntimeException ignorada) {
            return null;
        }
    }

    @FunctionalInterface
    protected interface ManejoDeError<T> {
        /** Devuelve el resultado a usar, lanza una excepción propia o devuelve null si no lo maneja. */
        T manejar(HttpStatusCode status, RestClientResponseException e);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CuerpoDeError(String error, String codigo, String mensaje) {
    }
}
