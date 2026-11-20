package com.ticketya.msvc.entradas.entities;

/**
 * VIGENTE -> UTILIZADA (validación de QR) o VIGENTE -> EN_REINTEGRO -> DEVUELTA.
 * Solo una de las dos transiciones que salen de VIGENTE puede completarse.
 */
public enum EstadoEntrada {
    VIGENTE,
    UTILIZADA,
    EN_REINTEGRO,
    DEVUELTA
}
