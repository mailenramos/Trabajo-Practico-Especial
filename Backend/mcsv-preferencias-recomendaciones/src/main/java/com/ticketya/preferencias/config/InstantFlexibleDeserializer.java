package com.ticketya.preferencias.config;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

public class InstantFlexibleDeserializer extends JsonDeserializer<Instant> {
    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String valor = parser.getValueAsString();
        if (valor == null || valor.isBlank()) {
            throw JsonMappingException.from(parser, "fechaHora no es una fecha ISO-8601 válida");
        }
        try {
            return Instant.parse(valor);
        } catch (DateTimeParseException instantError) {
            try {
                return LocalDateTime.parse(valor).atZone(BUENOS_AIRES).toInstant();
            } catch (DateTimeParseException localDateTimeError) {
                localDateTimeError.addSuppressed(instantError);
                throw JsonMappingException.from(parser, "fechaHora no es una fecha ISO-8601 válida",
                        localDateTimeError);
            }
        }
    }
}
