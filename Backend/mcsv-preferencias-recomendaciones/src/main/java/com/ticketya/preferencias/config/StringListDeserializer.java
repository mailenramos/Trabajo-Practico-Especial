package com.ticketya.preferencias.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

public class StringListDeserializer extends JsonDeserializer<List<String>> {
    @Override
    public List<String> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.isExpectedStartArrayToken()) {
            throw JsonMappingException.from(parser, "participantes debe ser una lista de textos");
        }

        List<String> valores = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token != JsonToken.VALUE_STRING) {
                throw JsonMappingException.from(parser, "participantes debe contener solo textos");
            }
            valores.add(parser.getText());
        }
        return valores;
    }
}
