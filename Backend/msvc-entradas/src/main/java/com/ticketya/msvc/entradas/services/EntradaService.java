package com.ticketya.msvc.entradas.services;

import com.ticketya.msvc.entradas.dto.EmisionRequestDTO;
import com.ticketya.msvc.entradas.dto.EntradaResponseDTO;
import com.ticketya.msvc.entradas.dto.PaginaEntradasResponseDTO;
import com.ticketya.msvc.entradas.dto.ReintegroEventoResponseDTO;
import com.ticketya.msvc.entradas.dto.ResultadoEmisionDTO;
import com.ticketya.msvc.entradas.entities.EstadoEntrada;

import java.util.UUID;

public interface EntradaService {

    // Públicos
    PaginaEntradasResponseDTO getEntradas(String idUsuario, EstadoEntrada estado,
                                          String idEvento, int page, int size);

    EntradaResponseDTO getEntrada(String idUsuario, UUID id);

    // Internos
    ResultadoEmisionDTO emitirEntradas(EmisionRequestDTO request);

    EntradaResponseDTO consultarEntradaInterna(UUID id);

    EntradaResponseDTO marcarEnReintegro(UUID id);

    EntradaResponseDTO marcarDevuelta(UUID id);

    ReintegroEventoResponseDTO reintegroPorCancelacionEvento(String idEvento);
}
