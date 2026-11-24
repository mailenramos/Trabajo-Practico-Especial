package ticketya.web.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /reportes/eventos/{eventoId}/devoluciones: entradas vendidas, devueltas (entradas distintas
 * con la baja completada, por origen DEVOLUCION y CANCELACION_EVENTO) y netas. Las devoluciones
 * en curso se informan aparte y no restan.
 */
public record ReporteDevolucionesRespuesta(@JsonUnwrapped EncabezadoReporte encabezado, Cantidades totales,
                                           List<Sector> sectores, CantidadImporte devolucionesEnCurso) {

    public record Cantidades(long entradasVendidas, long entradasDevueltas, long devueltasPorDevolucion,
                             long devueltasPorCancelacion, long entradasNetas, BigDecimal importeDevuelto,
                             BigDecimal importeDevueltoPorDevolucion, BigDecimal importeDevueltoPorCancelacion) {
    }

    public record Sector(String sectorId, String sectorNombre, @JsonUnwrapped Cantidades cantidades) {
    }
}
