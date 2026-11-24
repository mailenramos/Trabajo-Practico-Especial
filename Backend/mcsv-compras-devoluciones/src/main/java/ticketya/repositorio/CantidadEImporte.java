package ticketya.repositorio;

import java.math.BigDecimal;

/** Cantidad de registros y la suma de sus importes (reportes). Un importe nulo (sin filas) es 0. */
public record CantidadEImporte(long cantidad, BigDecimal importe) {
    public CantidadEImporte {
        importe = importe == null ? BigDecimal.ZERO : importe;
    }
}
