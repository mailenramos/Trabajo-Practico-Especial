package com.ticketya.preferencias.api;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ticketya.preferencias.config.InstantFlexibleDeserializer;
import com.ticketya.preferencias.config.StringListDeserializer;
import com.ticketya.preferencias.config.StrictStringDeserializer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EventoPublicadoAvisoRequest(
        @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class) String eventoId,
        @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class) String nombre,
        @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class) String tipo,
        @NotNull @JsonDeserialize(using = StringListDeserializer.class) List<@NotBlank String> participantes,
        @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class) String ciudad,
        @NotNull @JsonDeserialize(using = InstantFlexibleDeserializer.class) Instant fechaHora) {
}
