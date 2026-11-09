package com.ticketya.gestionlugares.api;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

/** Rechaza coerciones de cadenas y números decimales a Integer. */
public class StrictIntegerDeserializer extends JsonDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw JsonMappingException.from(parser, "limiteEntradasPorCliente debe ser un número entero JSON");
        }
        return parser.getIntValue();
    }

    @Override
    public Integer getNullValue(DeserializationContext context) throws JsonMappingException {
        throw JsonMappingException.from(context.getParser(), "limiteEntradasPorCliente no puede ser null");
    }
}
