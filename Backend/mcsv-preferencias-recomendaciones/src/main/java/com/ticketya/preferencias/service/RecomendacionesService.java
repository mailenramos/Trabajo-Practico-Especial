package com.ticketya.preferencias.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ticketya.preferencias.api.RecomendacionResponse;
import com.ticketya.preferencias.domain.Preferencia;
import com.ticketya.preferencias.integration.compras.ComprasClient;
import com.ticketya.preferencias.integration.compras.ComprasUnavailableException;
import com.ticketya.preferencias.integration.compras.SenalesCompras;
import com.ticketya.preferencias.integration.eventos.EventoPublicado;
import com.ticketya.preferencias.integration.eventos.EventosClient;

@Service
public class RecomendacionesService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecomendacionesService.class);
    private static final int MAX_RECOMMENDATIONS = 20;

    private final PreferenciasService preferenciasService;
    private final EventosClient eventosClient;
    private final ComprasClient comprasClient;
    private final Clock clock;

    public RecomendacionesService(PreferenciasService preferenciasService, EventosClient eventosClient,
            ComprasClient comprasClient, Clock recommendationClock) {
        this.preferenciasService = preferenciasService;
        this.eventosClient = eventosClient;
        this.comprasClient = comprasClient;
        this.clock = recommendationClock;
    }

    public List<RecomendacionResponse> recomendar(String usuarioId) {
        Instant ahora = clock.instant();
        Preferencia preferencias = preferenciasService.obtener(usuarioId);
        var eventos = eventosClient.listarTodos();
        SenalesCompras senalesCompras = obtenerSenalesCompras(usuarioId);

        Set<String> artistas = normalizar(preferencias.artistas());
        Set<String> tiposFavoritos = normalizar(preferencias.tiposEvento());
        Set<String> ciudades = normalizar(preferencias.ciudades());
        Set<String> tiposComprados = normalizar(senalesCompras.tiposComprados());
        Set<String> eventosCoComprados = normalizar(senalesCompras.eventosCoComprados());

        return eventos.stream()
                .filter(evento -> esElegible(evento, ahora))
                .map(evento -> crearRecomendacion(evento, artistas, tiposFavoritos, ciudades,
                        tiposComprados, eventosCoComprados))
                .filter(recomendacion -> recomendacion.puntuacion() > 0)
                .sorted(Comparator.comparingInt(RecomendacionResponse::puntuacion).reversed()
                        .thenComparing(RecomendacionResponse::fechaHora)
                        .thenComparing(RecomendacionResponse::eventoId))
                .limit(MAX_RECOMMENDATIONS)
                .toList();
    }

    private SenalesCompras obtenerSenalesCompras(String usuarioId) {
        try {
            return comprasClient.obtenerSenales(usuarioId);
        } catch (ComprasUnavailableException exception) {
            LOGGER.warn("No se pudieron obtener señales de Compras; se recomendará usando solo preferencias.");
            return SenalesCompras.vacias();
        }
    }

    private boolean esElegible(EventoPublicado evento, Instant ahora) {
        String estado = normalizar(evento.estado());
        return evento.fechaHora() != null
                && evento.fechaHora().isAfter(ahora)
                && ("publicado".equals(estado) || "agotado".equals(estado));
    }

    private RecomendacionResponse crearRecomendacion(EventoPublicado evento, Set<String> artistas,
            Set<String> tiposFavoritos, Set<String> ciudades, Set<String> tiposComprados,
            Set<String> eventosCoComprados) {
        String tipo = normalizar(evento.tipo());
        String ciudad = normalizar(evento.ciudad());
        int puntuacion = 0;
        if (evento.participantes() != null
                && evento.participantes().stream().map(RecomendacionesService::normalizar)
                        .filter(Objects::nonNull).anyMatch(artistas::contains)) {
            puntuacion += 5;
        }
        if (coincide(tiposFavoritos, tipo)) {
            puntuacion += 3;
        }
        if (coincide(ciudades, ciudad)) {
            puntuacion += 2;
        }
        if (coincide(tiposComprados, tipo)) {
            puntuacion++;
        }
        if (coincide(eventosCoComprados, normalizar(evento.eventoId()))) {
            puntuacion += 2;
        }

        return new RecomendacionResponse(evento.eventoId(), evento.nombre(), evento.tipo(),
                evento.participantes(), evento.ciudad(), evento.fechaHora(), evento.estado(), puntuacion);
    }

    private Set<String> normalizar(Collection<String> valores) {
        if (valores == null) {
            return Set.of();
        }
        return valores.stream()
                .map(RecomendacionesService::normalizar)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizar(String valor) {
        String limpio = limpiar(valor);
        return limpio == null ? null : limpio.toLowerCase(Locale.ROOT);
    }

    private static String limpiar(String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        return limpio.isEmpty() ? null : limpio;
    }

    private boolean coincide(Set<String> valoresNormalizados, String valorNormalizado) {
        return valorNormalizado != null && valoresNormalizados.contains(valorNormalizado);
    }
}
