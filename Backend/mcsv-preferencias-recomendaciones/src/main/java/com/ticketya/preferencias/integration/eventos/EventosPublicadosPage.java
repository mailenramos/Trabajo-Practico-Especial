package com.ticketya.preferencias.integration.eventos;

import java.util.List;

public record EventosPublicadosPage(
        List<EventoPublicado> contenido,
        int pagina,
        int tamano,
        long totalElementos,
        int totalPaginas) {
}
