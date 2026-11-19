package com.ticketya.preferencias.service;

public class NotificacionesPartialFailureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NotificacionesPartialFailureException(int destinatarios, int enviados, int fallidos) {
        super("No se pudieron entregar " + fallidos + " de " + destinatarios + " avisos; "
                + enviados + " fueron aceptados");
    }
}
