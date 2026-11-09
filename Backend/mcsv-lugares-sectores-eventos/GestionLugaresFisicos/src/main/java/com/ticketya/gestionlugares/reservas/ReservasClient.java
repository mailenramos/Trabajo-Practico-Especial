package com.ticketya.gestionlugares.reservas;

import com.ticketya.gestionlugares.reservas.dto.InicializarDisponibilidadRequest;
import com.ticketya.gestionlugares.reservas.dto.DisponibilidadEventoResponse;

/** Contrato del cliente de Disponibilidad y Reservas. */
public interface ReservasClient {
    void inicializarDisponibilidad(String eventoId, InicializarDisponibilidadRequest request);

    void cambiarEstadoVenta(String eventoId, EstadoVentaReservas estado);

    DisponibilidadEventoResponse consultarDisponibilidad(String eventoId);
}
