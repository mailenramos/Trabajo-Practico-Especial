package com.ticketya.msvc.entradas.exception;

/** HTTP 503: no se pudo completar una comprobación con otro microservicio. Se puede reintentar. */
public class ServicioNoDisponibleException extends RuntimeException {

    private final String codigo;

    public ServicioNoDisponibleException(String codigo, String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.codigo = codigo;
    }

    public String getCodigo() {
        return codigo;
    }
}
