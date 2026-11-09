package ar.edu.unicen.ms_disponibilidad_reservas.controller;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.LiberarReservaRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.CrearReservaRequest;
import tools.jackson.databind.json.JsonMapper;

/** Checks JSON property presence before reservation and release DTO deserialization. */
@ControllerAdvice
public class LiberacionBodyAdvice extends RequestBodyAdviceAdapter {
    private final JsonMapper mapper;
    public LiberacionBodyAdvice(JsonMapper mapper) { this.mapper = mapper; }

    @Override
    public boolean supports(MethodParameter parameter, Type type,
            Class<? extends HttpMessageConverter<?>> converter) {
        return parameter.getParameterType() == LiberarReservaRequest.class
                || parameter.getParameterType() == CrearReservaRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter,
            Type type, Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        byte[] bytes = input.getBody().readAllBytes();
        tools.jackson.databind.JsonNode tree;
        try {
            tree = mapper.readTree(bytes);
        } catch (tools.jackson.core.JacksonException invalidJson) {
            throw new org.springframework.http.converter.HttpMessageNotReadableException(
                    "JSON inválido", invalidJson, input);
        }
        if (tree != null && tree.isObject() && tree.has("motivo")
                && "PAGO_RECHAZADO".equals(tree.get("motivo").asText())
                && (tree.has("devolucionId") || tree.has("detalles"))) {
            throw new IllegalArgumentException("PAGO_RECHAZADO acepta únicamente motivo.");
        }
        if (tree != null && tree.isObject() && tree.has("detalles") && tree.get("detalles").isArray()) {
            for (var detalle : tree.get("detalles")) {
                if (!detalle.isObject()) continue; // DTO validation handles invalid element types.
                boolean sector = detalle.has("sectorId") && !detalle.get("sectorId").isNull();
                boolean numerado = detalle.has("fila") && !detalle.get("fila").isNull()
                        && detalle.has("numero") && !detalle.get("numero").isNull()
                        && !detalle.has("cantidad");
                boolean noNumerado = detalle.has("cantidad") && !detalle.get("cantidad").isNull()
                        && !detalle.has("fila") && !detalle.has("numero");
                if (!sector || (!numerado && !noNumerado)) {
                    throw new IllegalArgumentException(
                            "Cada detalle debe incluir sectorId y únicamente fila y numero, o cantidad, sin campos nulos.");
                }
            }
        }
        return new HttpInputMessage() {
            public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }
}
