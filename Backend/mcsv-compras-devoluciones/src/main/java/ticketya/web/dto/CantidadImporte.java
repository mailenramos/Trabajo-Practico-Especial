package ticketya.web.dto;

import java.math.BigDecimal;

/** Una cantidad de operaciones y su importe (por ejemplo, compras cobradas sin confirmar). */
public record CantidadImporte(long cantidad, BigDecimal importe) {
}
