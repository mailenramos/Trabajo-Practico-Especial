package ar.edu.unicen.ms_disponibilidad_reservas.controller;

import org.springframework.http.HttpStatus;

public class AccesoException extends RuntimeException {
    private final HttpStatus status;
    public AccesoException(HttpStatus status, String mensaje) { super(mensaje); this.status = status; }
    public HttpStatus status() { return status; }
}
