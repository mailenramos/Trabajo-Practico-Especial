package com.ticketya.preferencias.api;

import java.time.Instant;
import java.util.List;

import com.ticketya.preferencias.domain.Preferencia;

public record PreferenciaResponse(
        List<String> tiposEvento,
        List<String> artistas,
        List<String> ciudades,
        Instant actualizadoEn) {

    public static PreferenciaResponse desde(Preferencia preferencia) {
        return new PreferenciaResponse(preferencia.tiposEvento(), preferencia.artistas(),
                preferencia.ciudades(), preferencia.actualizadoEn());
    }
}
