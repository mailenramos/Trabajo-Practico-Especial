package ar.edu.unicen.ms_fila_virtual.service;

public class FilaConflictException extends RuntimeException {
    public FilaConflictException(String mensaje) { super(mensaje); }
}
