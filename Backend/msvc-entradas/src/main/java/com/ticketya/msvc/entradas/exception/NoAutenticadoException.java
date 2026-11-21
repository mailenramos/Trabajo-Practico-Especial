package com.ticketya.msvc.entradas.exception;

/** HTTP 401: falta la identidad del usuario o la credencial de servicio. */
public class NoAutenticadoException extends RuntimeException {

    private final String codigo;

    public NoAutenticadoException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public String getCodigo() {
        return codigo;
    }
}
