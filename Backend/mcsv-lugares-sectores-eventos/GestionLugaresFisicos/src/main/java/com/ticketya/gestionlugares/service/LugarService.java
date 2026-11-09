package com.ticketya.gestionlugares.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import com.ticketya.gestionlugares.api.CreateLugarRequest;
import com.ticketya.gestionlugares.api.CreateSectorRequest;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.Sector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.repository.LugarRepository;

@Service
public class LugarService {

    private final LugarRepository lugarRepository;

    public LugarService(LugarRepository lugarRepository) {
        this.lugarRepository = lugarRepository;
    }

    public Lugar crearLugar(CreateLugarRequest request) {
        Lugar lugar = new Lugar(
                new ObjectId().toHexString(),
                request.nombre().trim(),
                request.direccion().trim(),
                request.ciudad() == null || request.ciudad().isBlank() ? null : request.ciudad().trim(),
                request.latitud(),
                request.longitud(),
                List.of());
        return lugarRepository.save(lugar);
    }

    public List<Lugar> listarLugares() {
        return lugarRepository.findAll();
    }

    public Lugar buscarLugar(String id) {
        return lugarRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No existe el lugar " + id));
    }

    public List<Sector> listarSectores(String lugarId) {
        return buscarLugar(lugarId).getSectores();
    }

    public Sector agregarSector(String lugarId, CreateSectorRequest request) {
        Lugar lugar = buscarLugar(lugarId);
        Sector nuevoSector = crearSector(request);
        List<Sector> sectores = new ArrayList<>(lugar.getSectores());
        sectores.add(nuevoSector);
        lugar.setSectores(sectores);
        lugarRepository.save(lugar);
        return nuevoSector;
    }

    private Sector crearSector(CreateSectorRequest request) {
        if (request.nombre() == null || request.nombre().isBlank() || request.tipo() == null) {
            throw new IllegalArgumentException("El nombre y el tipo del sector son obligatorios");
        }
        if (request.tipo() == TipoSector.NUMERADO) {
            if (request.filas() == null || request.filas() <= 0
                    || request.asientosPorFila() == null || request.asientosPorFila() <= 0
                    || request.capacidad() != null) {
                throw new IllegalArgumentException(
                        "Un sector numerado requiere filas y asientosPorFila positivos, sin capacidad");
            }
            return new Sector(UUID.randomUUID().toString(), request.nombre().trim(), request.tipo(),
                    request.filas(), request.asientosPorFila(), null);
        }
        if (request.capacidad() == null || request.capacidad() <= 0
                || request.filas() != null || request.asientosPorFila() != null) {
            throw new IllegalArgumentException(
                    "Un sector no numerado requiere capacidad positiva, sin filas ni asientosPorFila");
        }
        return new Sector(UUID.randomUUID().toString(), request.nombre().trim(), request.tipo(),
                null, null, request.capacidad());
    }
}
