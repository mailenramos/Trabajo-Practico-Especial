package ticketya.web.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /reportes/eventos/{eventoId}/recaudacion: bruta (precios históricos de compras CONFIRMADAS),
 * reintegros EXITOSOS de esas compras y neta. Los reintegros sin resultado exitoso y lo cobrado en
 * compras que no se confirmaron se informan aparte.
 */
public record ReporteRecaudacionRespuesta(@JsonUnwrapped EncabezadoReporte encabezado, Montos totales,
                                          List<Sector> sectores, Reconciliacion pendientesDeReconciliacion,
                                          NoConfirmadas comprasNoConfirmadas) {

    public record Montos(BigDecimal recaudacionBruta, BigDecimal reintegrosExitosos, BigDecimal recaudacionNeta) {
    }

    public record Sector(String sectorId, String sectorNombre, @JsonUnwrapped Montos montos) {
    }

    /** Reintegros de compras confirmadas que todavía no salieron bien. */
    public record Reconciliacion(CantidadImporte reintegrosPendientes, CantidadImporte reintegrosFallidos) {
    }

    /** Compras cobradas que no llegaron a confirmarse (pendientes o fallidas) y lo ya reintegrado de ellas. */
    public record NoConfirmadas(long compras, BigDecimal cobrado, BigDecimal reintegrado) {
    }
}
