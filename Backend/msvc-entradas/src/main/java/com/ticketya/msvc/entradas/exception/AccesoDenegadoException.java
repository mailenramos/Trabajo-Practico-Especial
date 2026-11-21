package com.ticketya.msvc.entradas.exception;

/** HTTP 403. El código por defecto es ACCESO_DENEGADO. */
public class AccesoDenegadoException extends RuntimeException {

    private final String codigo;

    public AccesoDenegadoException(String mensaje) {
        this("ACCESO_DENEGADO", mensaje);
    }

    public AccesoDenegadoException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public String getCodigo() {
        return codigo;
    }
}
