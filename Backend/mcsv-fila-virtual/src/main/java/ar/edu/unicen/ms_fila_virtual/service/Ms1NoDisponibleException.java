package ar.edu.unicen.ms_fila_virtual.service;

public class Ms1NoDisponibleException extends RuntimeException {
    public Ms1NoDisponibleException(String mensaje) { super(mensaje); }
    public Ms1NoDisponibleException(String mensaje, Throwable causa) { super(mensaje, causa); }
}
