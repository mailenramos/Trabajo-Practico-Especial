package com.ticketya.gestionlugares.api;

import java.util.List;

public record EventoBusquedaResponse(List<EventoBusquedaItem> contenido, int pagina, int tamano,
        long totalElementos, int totalPaginas) { }
