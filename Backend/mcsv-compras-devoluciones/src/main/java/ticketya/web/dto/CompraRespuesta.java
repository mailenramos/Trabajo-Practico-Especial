package ticketya.web.dto;

import ticketya.modelo.Compra;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Compra con su detalle. Los precios son los vigentes al momento de comprar. */
public record CompraRespuesta(UUID id, EstadoCompra estado, String reservaId, EventoRespuesta evento,
                              List<LugarRespuesta> lugares, BigDecimal total, String medioDePago,
                              Instant fecha, List<ReintegroDeCompra> reintegros) {

    public record EventoRespuesta(String id, String nombre, Instant fechaInicio) {
    }

    /** Un lugar comprado. entradaId queda vacío mientras la entrada no se emitió. */
    public record LugarRespuesta(String sectorId, String sectorNombre, Integer fila, Integer numero,
                                 BigDecimal precioUnitario, String entradaId) {
    }

    /** Un reintegro pedido sobre la compra (devolución de una entrada o venta rechazada). */
    public record ReintegroDeCompra(UUID id, BigDecimal monto, OrigenReintegro origen, EstadoReintegro estado) {
    }

    /** Compra sin reintegros (por ejemplo, recién iniciada). */
    public static CompraRespuesta de(Compra c) {
        return de(c, List.of());
    }

    public static CompraRespuesta de(Compra c, List<Reintegro> reintegros) {
        return new CompraRespuesta(c.getId(), c.getEstado(), c.getReservaId(),
                new EventoRespuesta(c.getEventoId(), c.getEventoNombre(), c.getEventoFechaInicio()),
                c.getDetalle().stream().map(CompraRespuesta::lugar).toList(),
                c.getTotal(), c.getMedioDePago(), c.getFecha(),
                reintegros.stream().map(r -> new ReintegroDeCompra(r.getId(), r.getMonto(), r.getOrigen(),
                        r.getEstado())).toList());
    }

    private static LugarRespuesta lugar(DetalleCompra d) {
        return new LugarRespuesta(d.getSectorId(), d.getSectorNombre(), d.getFila(), d.getNumero(),
                d.getPrecioUnitario(), d.getEntradaId());
    }
}
