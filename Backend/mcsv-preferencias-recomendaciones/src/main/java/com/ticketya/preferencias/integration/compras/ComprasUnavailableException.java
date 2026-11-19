package com.ticketya.preferencias.integration.compras;

public class ComprasUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ComprasUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ComprasUnavailableException(String message) {
        super(message);
    }
}
