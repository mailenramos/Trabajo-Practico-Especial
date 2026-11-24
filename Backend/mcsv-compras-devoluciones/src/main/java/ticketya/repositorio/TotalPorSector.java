package ticketya.repositorio;

import java.math.BigDecimal;

/** Cantidad de entradas y su importe en un sector (reportes). Un importe nulo (sin filas) es 0. */
public record TotalPorSector(String sectorId, String sectorNombre, long cantidad, BigDecimal importe) {
    public TotalPorSector {
        importe = importe == null ? BigDecimal.ZERO : importe;
    }
}
