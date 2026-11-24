package ticketya.repositorio;

import ticketya.modelo.EstadoReintegro;

import java.math.BigDecimal;

/** Cantidad e importe de reintegros en un estado (reporte de recaudación). */
public record ReintegrosPorEstado(EstadoReintegro estado, long cantidad, BigDecimal importe) {
    public ReintegrosPorEstado {
        importe = importe == null ? BigDecimal.ZERO : importe;
    }
}
