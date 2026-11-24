package ticketya.web.dto;

import java.time.Instant;

/** Datos comunes a los cuatro reportes de un evento. */
public record EncabezadoReporte(String eventoId, String eventoNombre, String estadoEvento, Instant generadoEn,
                                boolean hayOperacionesPendientes) {
}
