package com.ticketya.gestionlugares.compras;

import java.time.Instant;

public interface ComprasClient {
    void notificarCancelacion(String eventoId, String motivo, Instant canceladoEn);
}
