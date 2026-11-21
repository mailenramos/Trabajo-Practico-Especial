package ar.edu.unicen.ms_fila_virtual.controller;

import org.springframework.http.HttpStatus;

class AccesoException extends RuntimeException {
    private final HttpStatus status;
    AccesoException(HttpStatus status, String mensaje) { super(mensaje); this.status = status; }
    HttpStatus status() { return status; }
}
