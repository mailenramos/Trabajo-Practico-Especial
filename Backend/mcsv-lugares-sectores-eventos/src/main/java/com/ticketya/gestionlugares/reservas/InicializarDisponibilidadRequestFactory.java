package com.ticketya.gestionlugares.reservas;

import java.util.ArrayList;
import java.util.List;

import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.reservas.dto.AsientoReservas;
import com.ticketya.gestionlugares.reservas.dto.InicializarDisponibilidadRequest;
import com.ticketya.gestionlugares.reservas.dto.SectorDisponibilidadRequest;

public final class InicializarDisponibilidadRequestFactory {

    private InicializarDisponibilidadRequestFactory() {
    }

    public static InicializarDisponibilidadRequest desdeEvento(Evento evento) {
        List<SectorDisponibilidadRequest> sectores = evento.getSectores().stream()
                .map(InicializarDisponibilidadRequestFactory::desdeSector)
                .toList();
        return new InicializarDisponibilidadRequest(evento.getLimiteEntradasPorCliente(), sectores);
    }

    private static SectorDisponibilidadRequest desdeSector(EventoSector sector) {
        if (sector.getTipo() == TipoSector.NUMERADO) {
            int filas = sector.getFilas();
            int asientosPorFila = sector.getAsientosPorFila();
            int capacidad = Math.multiplyExact(filas, asientosPorFila);
            List<AsientoReservas> asientos = new ArrayList<>(capacidad);
            for (int fila = 1; fila <= filas; fila++) {
                for (int numero = 1; numero <= asientosPorFila; numero++) {
                    asientos.add(new AsientoReservas(fila, numero));
                }
            }
            return new SectorDisponibilidadRequest(sector.getSectorId(), sector.getTipo(), capacidad, asientos);
        }
        return new SectorDisponibilidadRequest(sector.getSectorId(), sector.getTipo(),
                sector.getCapacidad(), List.of());
    }
}
