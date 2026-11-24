package ticketya.repositorio;

import ticketya.modelo.EstadoReintegro;

import java.math.BigDecimal;

/** Importe de reintegros de un estado atribuido a un sector (reporte de recaudación). */
public record ReintegroPorSector(String sectorId, String sectorNombre, EstadoReintegro estado, BigDecimal importe) {
    public ReintegroPorSector {
        importe = importe == null ? BigDecimal.ZERO : importe;
    }
}
