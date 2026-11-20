package ticketya.modelo;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Guarda los datos del aviso como texto JSON. */
@Converter
public class DatosJsonConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> TIPO = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Map<String, Object> datos) {
        return JSON.writeValueAsString(datos == null ? Map.of() : datos);
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String texto) {
        if (texto == null || texto.isBlank()) {
            return new LinkedHashMap<>();
        }
        return JSON.readValue(texto, TIPO);
    }
}
