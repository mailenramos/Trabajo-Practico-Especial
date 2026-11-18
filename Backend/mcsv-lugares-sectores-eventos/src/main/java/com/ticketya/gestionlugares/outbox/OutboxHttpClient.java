package com.ticketya.gestionlugares.outbox;

import com.ticketya.gestionlugares.domain.AvisoOutbox;

public interface OutboxHttpClient {

    int enviar(AvisoOutbox aviso);
}
