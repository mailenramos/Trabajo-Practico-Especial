package ticketya.web.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /reportes/eventos/{eventoId}/ventas: solo compras CONFIRMADAS, con los precios históricos.
 * Las compras cobradas con la emisión sin terminar se informan aparte.
 */
public record ReporteVentasRespuesta(@JsonUnwrapped EncabezadoReporte encabezado, Ventas totales,
                                     List<Sector> sectores, CantidadImporte comprasConEmisionIncompleta) {

    public record Ventas(long comprasConfirmadas, long entradasVendidas, BigDecimal importeVendido) {
    }

    public record Sector(String sectorId, String sectorNombre, long entradasVendidas, BigDecimal importeVendido) {
    }
}
