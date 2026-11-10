package com.ticketya.gestionlugares.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum EstadoEvento {
    BORRADOR("borrador"),
    PUBLICADO("publicado"),
    AGOTADO("agotado"),
    CANCELADO("cancelado"),
    FINALIZADO("finalizado");

    private final String valor;

    EstadoEvento(String valor) {
        this.valor = valor;
    }

    @JsonValue
    public String getValor() {
        return valor;
    }
}
