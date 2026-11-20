package com.ticketya.msvc.entradas.clientes;

/** Consulta a Lugares, sectores y eventos (MS1) si un usuario tiene asignación activa a un evento. */
public interface AutorizacionAccesoCliente {

    /**
     * true solo si existe una asignación activa y no revocada. Lee siempre el estado actual.
     *
     * @throws com.ticketya.msvc.entradas.exception.ServicioNoDisponibleException si MS1 no responde,
     *         responde con error o excede el tiempo de espera
     */
    boolean estaAutorizado(String idEvento, String idUsuario);
}
