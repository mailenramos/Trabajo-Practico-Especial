package com.ticketya.gestionlugares.outbox;

import java.time.Instant;
import java.util.Map;

import com.ticketya.gestionlugares.compras.EventoCanceladoRequest;
import com.ticketya.gestionlugares.compras.http.ComprasProperties;
import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;

final class CancelacionAvisoOutboxFactory {

    private static final String DESTINO_CANCELACION_COMPRAS = "/eventos-cancelados";

    private CancelacionAvisoOutboxFactory() {
    }

    static AvisoOutbox crear(String eventoId, String motivo, Instant canceladoEn, Instant ahora,
            ComprasProperties comprasProperties) {
        EventoCanceladoRequest request = new EventoCanceladoRequest(eventoId, motivo, canceladoEn.toString());
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setId("cancelacion-" + eventoId);
        aviso.setTipo("CANCELACION");
        aviso.setEventoId(eventoId);
        aviso.setDestino(comprasProperties.getBaseUrl().resolve(DESTINO_CANCELACION_COMPRAS).toString());
        aviso.setPayload(Map.of(
                "eventoId", request.eventoId(),
                "motivo", request.motivo(),
                "canceladoEn", request.canceladoEn()));
        aviso.setEstado(EstadoAvisoOutbox.PENDIENTE);
        aviso.setIntentos(0);
        aviso.setProximoIntento(ahora);
        aviso.setCreadoEn(ahora);
        aviso.setActualizadoEn(ahora);
        return aviso;
    }
}
