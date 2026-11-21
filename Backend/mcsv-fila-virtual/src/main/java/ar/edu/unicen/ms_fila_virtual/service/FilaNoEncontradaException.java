package ar.edu.unicen.ms_fila_virtual.service;

public class FilaNoEncontradaException extends RuntimeException {
    public FilaNoEncontradaException(String mensaje) { super(mensaje); }
}
