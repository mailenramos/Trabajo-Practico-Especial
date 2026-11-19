package com.ticketya.preferencias.integration.notificaciones;

public class NotificacionesUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NotificacionesUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public NotificacionesUnavailableException(String message) {
        super(message);
    }
}
