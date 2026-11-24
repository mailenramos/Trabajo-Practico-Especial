package ticketya.web.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /reportes/eventos/{eventoId}/ocupacion: el inventario actual según Disponibilidad y reservas.
 * La ocupación es vendidos / capacidad × 100; las reservas temporales se informan aparte.
 */
public record ReporteOcupacionRespuesta(@JsonUnwrapped EncabezadoReporte encabezado, boolean inventarioInicializado,
                                        Ocupacion totales, List<Sector> sectores) {

    public record Ocupacion(int capacidad, int vendidos, int reservasTemporales, BigDecimal porcentajeOcupacion) {
    }

    public record Sector(String sectorId, String sectorNombre, @JsonUnwrapped Ocupacion ocupacion) {
    }
}
