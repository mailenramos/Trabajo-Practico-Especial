package ticketya.servicio;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ticketya.cliente.EventosCliente;
import ticketya.cliente.EventosCliente.Evento;
import ticketya.cliente.EventosCliente.Sector;
import ticketya.cliente.ReservasCliente;
import ticketya.cliente.ReservasCliente.DetalleReserva;
import ticketya.cliente.ReservasCliente.Reserva;
import ticketya.error.ApiException;
import ticketya.modelo.Compra;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EstadoCompra;
import ticketya.repositorio.CompraRepository;
import ticketya.repositorio.ReintegroRepository;
import ticketya.web.UsuarioActual;
import ticketya.web.dto.CompraRespuesta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Inicio de la compra a partir de una reserva (C-1, P-3). */
@Service
public class CompraService {

    private final ReservasCliente reservas;
    private final EventosCliente eventos;
    private final CompraRepository compras;
    private final ReintegroRepository reintegros;

    public CompraService(ReservasCliente reservas, EventosCliente eventos, CompraRepository compras,
                         ReintegroRepository reintegros) {
        this.reservas = reservas;
        this.eventos = eventos;
        this.compras = compras;
        this.reintegros = reintegros;
    }

    /** Resultado de iniciar una compra: nueva (201) o la pendiente ya existente (200). */
    public record InicioCompra(CompraRespuesta compra, boolean nueva) {
    }

    /**
     * Registra una compra pendiente para una reserva propia y vigente, con una fila de
     * detalle por lugar y el precio de cada entrada al momento de comprar. Una reserva
     * tiene una sola compra: si el mismo cliente repite el pedido con la compra pendiente,
     * recibe esa compra sin consultar de nuevo a Reservas ni a Eventos.
     */
    public InicioCompra iniciarCompra(UsuarioActual usuario, String reservaId) {
        var existente = compras.findByReservaId(reservaId);
        if (existente.isPresent()) {
            return compraExistente(usuario, existente.get().getId());
        }
        Reserva reserva = reservaPropiaVigente(usuario, reservaId);
        Evento evento = eventoDe(reserva);

        Compra compra = new Compra();
        compra.setClienteId(usuario.id());
        compra.setReservaId(reserva.reservaId());
        compra.setEventoId(evento.eventoId());
        compra.setEventoNombre(evento.nombre());
        compra.setEventoFechaInicio(evento.fechaInicio());

        BigDecimal total = BigDecimal.ZERO;
        for (DetalleReserva lugar : reserva.detalles()) {
            Sector sector = evento.sector(lugar.sectorId())
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_GATEWAY, "SECTOR_NO_ENCONTRADO",
                            "El evento " + evento.eventoId() + " no tiene el sector " + lugar.sectorId()));
            BigDecimal precio = sector.precio().setScale(2, RoundingMode.HALF_UP);
            // En sectores no numerados la reserva trae una cantidad: una fila por cada unidad
            int unidades = lugar.cantidad() != null ? lugar.cantidad() : 1;
            for (int i = 0; i < unidades; i++) {
                DetalleCompra d = new DetalleCompra();
                d.setSectorId(sector.sectorId());
                d.setSectorNombre(sector.nombre());
                if (lugar.cantidad() == null) {
                    d.setFila(lugar.fila());
                    d.setNumero(lugar.numero());
                }
                d.setPrecioUnitario(precio);
                compra.agregarDetalle(d);
                total = total.add(precio);
            }
        }
        if (compra.getDetalle().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "RESERVA_SIN_LUGARES",
                    "La reserva " + reservaId + " no tiene lugares");
        }
        compra.setTotal(total.setScale(2, RoundingMode.HALF_UP));

        try {
            return new InicioCompra(CompraRespuesta.de(compras.saveAndFlush(compra)), true);
        } catch (DataIntegrityViolationException e) {
            // Otro pedido registró una compra para la misma reserva al mismo tiempo
            Compra ganadora = compras.findByReservaId(reservaId).orElseThrow(() -> e);
            return compraExistente(usuario, ganadora.getId());
        }
    }

    /**
     * La reserva ya tiene compra: si es del usuario y sigue pendiente se devuelve esa;
     * si ya se resolvió, 409; si es de otro cliente, 403.
     */
    private InicioCompra compraExistente(UsuarioActual usuario, UUID compraId) {
        Compra compra = compras.buscarConDetalle(compraId).orElseThrow();
        if (!compra.getClienteId().equals(usuario.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "RESERVA_AJENA",
                    "La reserva " + compra.getReservaId() + " pertenece a otro cliente");
        }
        if (compra.getEstado() != EstadoCompra.PENDIENTE) {
            throw new ApiException(HttpStatus.CONFLICT, "COMPRA_YA_INICIADA",
                    "La reserva " + compra.getReservaId() + " ya tiene la compra " + compra.getId()
                            + " en estado " + compra.getEstado());
        }
        return new InicioCompra(CompraRespuesta.de(compra), false);
    }

    /** C-6: las compras del usuario, de la más reciente a la más vieja. */
    @Transactional(readOnly = true)
    public List<CompraRespuesta> listar(UsuarioActual usuario) {
        return compras.findByClienteIdOrderByFechaDesc(usuario.id()).stream()
                .map(c -> CompraRespuesta.de(c, reintegros.findByCompraIdOrderByCreadoEnAsc(c.getId())))
                .toList();
    }

    /** C-6 y P-1: una compra con su detalle, solo si es del usuario. */
    @Transactional(readOnly = true)
    public CompraRespuesta obtener(UsuarioActual usuario, UUID compraId) {
        Compra compra = compras.buscarConDetalle(compraId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COMPRA_NO_ENCONTRADA",
                        "No existe la compra " + compraId));
        if (!compra.getClienteId().equals(usuario.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO",
                    "La compra " + compraId + " pertenece a otro cliente");
        }
        return CompraRespuesta.de(compra, reintegros.findByCompraIdOrderByCreadoEnAsc(compraId));
    }

    /** Verifica que la reserva exista, sea del usuario y siga vigente. */
    Reserva reservaPropiaVigente(UsuarioActual usuario, String reservaId) {
        Reserva reserva = reservas.obtenerReserva(reservaId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESERVA_NO_ENCONTRADA",
                        "No existe la reserva " + reservaId));
        if (!usuario.id().equals(reserva.clienteId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "RESERVA_AJENA",
                    "La reserva " + reservaId + " pertenece a otro cliente");
        }
        if (!reserva.estaVigente(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESERVA_NO_VIGENTE",
                    "La reserva " + reservaId + " está vencida, liberada o ya confirmada. Hay que reservar de nuevo");
        }
        return reserva;
    }

    /** Consulta el evento de la reserva: nombre, fecha de inicio y precios de los sectores. */
    Evento eventoDe(Reserva reserva) {
        return eventos.obtenerEvento(reserva.eventoId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_GATEWAY, "EVENTO_NO_ENCONTRADO",
                        "El servicio de eventos no encontró el evento " + reserva.eventoId() + " de la reserva"));
    }

}
