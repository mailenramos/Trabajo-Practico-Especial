package ar.edu.unicen.ms_disponibilidad_reservas.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class Ms1EventoClient {
    private final ClienteHttpInterno http;
    private final JsonMapper mapper;

    public Ms1EventoClient(@Value("${ms1.api.base-url:http://localhost:8081}") String url,
            @Value("${ms2.ms1.connect-timeout-ms:2000}") long conexion,
            @Value("${ms2.ms1.read-timeout-ms:5000}") long lectura, JsonMapper mapper) {
        this.http = new ClienteHttpInterno(url, conexion, lectura, "MS1");
        this.mapper = mapper;
    }

    public EventoResponse consultar(String eventoId, String correlationId) {
        return http.get("/internal/eventos/{eventoId}", eventoId, correlationId, this::validarRespuesta);
    }

    private EventoResponse validarRespuesta(String body) {
        try {
            var json = mapper.readTree(body);
            if (json == null || !json.isObject() || !json.path("requiereFila").isBoolean()) throw invalida();
            return new EventoResponse(json.path("requiereFila").booleanValue());
        } catch (IntegracionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalida();
        }
    }

    private IntegracionException invalida() {
        return new IntegracionException(IntegracionException.Tipo.RESPUESTA_INVALIDA, "Respuesta inválida de MS1.");
    }

    public record EventoResponse(boolean requiereFila) { }
}
