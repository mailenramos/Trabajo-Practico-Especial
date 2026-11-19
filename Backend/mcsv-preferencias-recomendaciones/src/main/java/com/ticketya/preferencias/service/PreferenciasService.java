package com.ticketya.preferencias.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.ticketya.preferencias.api.ActualizarPreferenciasRequest;
import com.ticketya.preferencias.domain.Preferencia;
import com.ticketya.preferencias.repository.PreferenciasRepository;

@Service
public class PreferenciasService {
    private final PreferenciasRepository preferenciasRepository;

    public PreferenciasService(PreferenciasRepository preferenciasRepository) {
        this.preferenciasRepository = preferenciasRepository;
    }

    public Preferencia reemplazar(String usuarioId, ActualizarPreferenciasRequest request) {
        if (usuarioId == null || usuarioId.isBlank()) {
            throw new IllegalArgumentException("X-User-Id es obligatorio");
        }
        if (request == null) {
            throw new IllegalArgumentException("El cuerpo de preferencias es obligatorio");
        }

        Preferencia preferencia = new Preferencia(
                usuarioId,
                normalizar(request.tiposEvento(), "tiposEvento"),
                normalizar(request.artistas(), "artistas"),
                normalizar(request.ciudades(), "ciudades"),
                Instant.now());
        return preferenciasRepository.save(preferencia);
    }

    public Preferencia obtener(String usuarioId) {
        return preferenciasRepository.findById(usuarioId)
                .orElseGet(() -> new Preferencia(usuarioId, List.of(), List.of(), List.of(), null));
    }

    public void eliminar(String usuarioId) {
        preferenciasRepository.deleteById(usuarioId);
    }

    private List<String> normalizar(List<String> valores, String campo) {
        if (valores == null) {
            throw new PreferenciasInvalidasException(campo, "La lista " + campo + " es obligatoria");
        }

        List<String> normalizados = new ArrayList<>(valores.size());
        Set<String> vistos = new HashSet<>();
        for (String valor : valores) {
            if (valor == null || valor.strip().isEmpty()) {
                throw new PreferenciasInvalidasException(campo, "La lista " + campo + " no admite textos vacíos");
            }
            String normalizado = valor.strip();
            if (!vistos.add(normalizado)) {
                throw new PreferenciasInvalidasException(campo, "La lista " + campo + " no admite valores repetidos");
            }
            normalizados.add(normalizado);
        }
        return List.copyOf(normalizados);
    }
}
