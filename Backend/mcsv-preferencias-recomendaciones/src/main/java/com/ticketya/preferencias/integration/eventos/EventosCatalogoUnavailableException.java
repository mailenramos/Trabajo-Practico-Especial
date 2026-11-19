package com.ticketya.preferencias.integration.eventos;

public class EventosCatalogoUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EventosCatalogoUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public EventosCatalogoUnavailableException(String message) {
        super(message);
    }
}
