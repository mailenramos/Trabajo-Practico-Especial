package com.ticketya.gestionlugares.outbox;

import java.time.Instant;

import org.springframework.web.util.UriComponentsBuilder;

import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.filas.http.FilasProperties;
import com.ticketya.gestionlugares.trazabilidad.Correlacion;

final class CancelacionFilaAvisoOutboxFactory {
    private CancelacionFilaAvisoOutboxFactory() { }

    static AvisoOutbox crear(String eventoId, Instant ahora, FilasProperties filasProperties) {
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setId("cancelacion-fila-" + eventoId);
        aviso.setTipo("CANCELACION_FILA");
        aviso.setEventoId(eventoId);
        aviso.setMetodoHttp("PUT");
        aviso.setDestino(UriComponentsBuilder.fromUri(filasProperties.getBaseUrl())
                .pathSegment("internal", "filas", "eventos", "{eventoId}", "cancelacion")
                .encode().buildAndExpand(eventoId).toUriString());
        aviso.setPayload(null);
        aviso.setCorrelationId(Correlacion.actual());
        aviso.setEstado(EstadoAvisoOutbox.PENDIENTE);
        aviso.setIntentos(0);
        aviso.setProximoIntento(ahora);
        aviso.setCreadoEn(ahora);
        aviso.setActualizadoEn(ahora);
        return aviso;
    }
}
