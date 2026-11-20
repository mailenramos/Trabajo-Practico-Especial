package com.ticketya.msvc.entradas.services;

import com.ticketya.msvc.entradas.dto.EmisionRequestDTO;
import com.ticketya.msvc.entradas.dto.EntradaResponseDTO;
import com.ticketya.msvc.entradas.dto.EntradasCompraResponseDTO;
import com.ticketya.msvc.entradas.dto.PaginaEntradasResponseDTO;
import com.ticketya.msvc.entradas.dto.ReintegroEventoResponseDTO;
import com.ticketya.msvc.entradas.dto.ResultadoEmisionDTO;
import com.ticketya.msvc.entradas.entities.EstadoEntrada;
import com.ticketya.msvc.entradas.seguridad.UsuarioActual;

import java.util.UUID;

public interface EntradaService {

    // Públicos (cliente o administrador, vía API Gateway)

    /** CLIENTE: solo las propias. ADMIN: todas, con filtro opcional por cliente. */
    PaginaEntradasResponseDTO getEntradas(UsuarioActual usuario, EstadoEntrada estado, String idEvento,
                                          String idCliente, int page, int size);

    EntradaResponseDTO getEntrada(UsuarioActual usuario, UUID id);

    // Internos (Compras y devoluciones)

    ResultadoEmisionDTO emitirEntradas(EmisionRequestDTO request);

    EntradaResponseDTO consultarEntradaInterna(UUID id);

    EntradasCompraResponseDTO consultarEntradasDeCompra(UUID idCompra);

    EntradaResponseDTO marcarEnReintegro(UUID id);

    EntradaResponseDTO marcarDevuelta(UUID id);

    /** Cierra el evento (persistente e idempotente) y pasa sus entradas VIGENTE a EN_REINTEGRO. */
    ReintegroEventoResponseDTO reintegroPorCancelacionEvento(String idEvento);
}
