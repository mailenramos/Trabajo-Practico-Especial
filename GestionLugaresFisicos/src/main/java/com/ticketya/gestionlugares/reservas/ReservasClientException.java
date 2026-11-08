package com.ticketya.gestionlugares.reservas;

public class ReservasClientException extends RuntimeException {
    public ReservasClientException(String message, Throwable cause) {
        super(message, cause);
    }

    public ReservasClientException(String message) {
        super(message);
    }
}
