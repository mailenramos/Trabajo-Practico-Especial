package com.ticketya.gestionlugares.api;

import java.util.List;

public record EventoPublicadosResponse(List<EventoPublicadoItem> contenido, int pagina, int tamano,
        long totalElementos, int totalPaginas) { }
