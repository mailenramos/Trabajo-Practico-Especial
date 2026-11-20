package ar.edu.unicen.ms_disponibilidad_reservas.client;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class Ms7FilaClient {
    private final ClienteHttpInterno http;
    private final JsonMapper mapper;

    public Ms7FilaClient(@Value("${ms7.api.base-url:http://localhost:8088}") String url,
            @Value("${ms2.ms7.connect-timeout-ms:2000}") long conexion,
            @Value("${ms2.ms7.read-timeout-ms:5000}") long lectura, JsonMapper mapper) {
        this.http = new ClienteHttpInterno(url, conexion, lectura, "MS7");
        this.mapper = mapper;
    }

    public ConsumoResponse consumir(String eventoId, String usuarioId, String solicitudId,
            String huellaSolicitud, String correlationId) {
        return http.post("/internal/filas/eventos/{eventoId}/consumos", eventoId,
                new ConsumoRequest(usuarioId, solicitudId, huellaSolicitud), correlationId,
                body -> validarRespuesta(body, eventoId, solicitudId));
    }

    private ConsumoResponse validarRespuesta(String body, String eventoId, String solicitudId) {
        try {
            JsonNode json = mapper.readTree(body);
            if (json == null || !json.isObject() || !json.path("turnoId").isIntegralNumber()
                    || !json.path("turnoId").canConvertToLong() || json.path("turnoId").longValue() <= 0
                    || !textoIgual(json, "eventoId", eventoId) || !textoIgual(json, "solicitudId", solicitudId)
                    || !textoIgual(json, "estado", "CONSUMIDO") || !json.path("expiraEn").isTextual()
                    || !json.path("autorizacionVigente").isBoolean()) throw invalida();
            Instant expiraEn = Instant.parse(json.path("expiraEn").textValue());
            if (!json.path("autorizacionVigente").booleanValue()) {
                throw new IntegracionException(IntegracionException.Tipo.AUTORIZACION_NO_VIGENTE,
                        "La autorización del turno ya no está vigente.");
            }
            return new ConsumoResponse(json.path("turnoId").longValue(), eventoId, solicitudId,
                    "CONSUMIDO", expiraEn, true);
        } catch (IntegracionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalida();
        }
    }

    private boolean textoIgual(JsonNode json, String campo, String valor) {
        return json.path(campo).isTextual() && json.path(campo).textValue().equals(valor);
    }

    private IntegracionException invalida() {
        return new IntegracionException(IntegracionException.Tipo.RESPUESTA_INVALIDA, "Respuesta inválida de MS7.");
    }

    public record ConsumoRequest(String usuarioId, String solicitudId, String huellaSolicitud) { }
    public record ConsumoResponse(long turnoId, String eventoId, String solicitudId, String estado,
            Instant expiraEn, boolean autorizacionVigente) { }
}
