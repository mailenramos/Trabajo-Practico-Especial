package com.ticketya.gestionlugares.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ActualizarLimiteEntradasRequest(
        @NotNull @Min(1)
        @JsonDeserialize(using = StrictIntegerDeserializer.class)
        @JsonSetter(nulls = Nulls.FAIL)
        Integer limiteEntradasPorCliente) {
}
