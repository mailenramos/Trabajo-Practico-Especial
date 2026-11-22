package com.ticketya.gestionlugares.reservas;

public class ReservasClientException extends RuntimeException {
    private final Integer status;

    public ReservasClientException(String message, Throwable cause) {
        super(message, cause);
        this.status = null;
    }

    public ReservasClientException(String message) {
        super(message);
        this.status = null;
    }

    public ReservasClientException(String message, int status) {
        super(message);
        this.status = status;
    }

    public boolean esRespuesta4xx() {
        return status != null && status >= 400 && status < 500;
    }
}
