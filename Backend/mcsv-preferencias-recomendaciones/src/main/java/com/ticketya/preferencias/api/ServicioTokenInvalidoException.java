package com.ticketya.preferencias.api;

public class ServicioTokenInvalidoException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ServicioTokenInvalidoException() {
        super("X-Service-Token ausente o inválido");
    }
}
