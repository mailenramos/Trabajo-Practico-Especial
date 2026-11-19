package com.ticketya.preferencias.service;

public class PreferenciasInvalidasException extends IllegalArgumentException {
    private final String campo;

    public PreferenciasInvalidasException(String campo, String message) {
        super(message);
        this.campo = campo;
    }

    public String getCampo() {
        return campo;
    }
}
