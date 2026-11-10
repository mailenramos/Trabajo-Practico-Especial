package ticketya.dobles;

import ticketya.cliente.ReservasCliente;
import ticketya.cliente.ServicioExternoException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Doble de prueba del servicio de reservas. Como el real, confirmar es repetible y una
 * reserva vencida o liberada rechaza la confirmación.
 */
public class ReservasFalso implements ReservasCliente {

    private final Map<String, Reserva> reservas = new ConcurrentHashMap<>();
    private final AtomicInteger confirmaciones = new AtomicInteger();
    private final AtomicInteger liberaciones = new AtomicInteger();
    private volatile boolean caido;
    /** Si no es null, la próxima confirmación se rechaza con este código. */
    private volatile String rechazoDeConfirmacion;

    public void agregar(Reserva reserva) {
        reservas.put(reserva.reservaId(), reserva);
    }

    public Reserva reserva(String reservaId) {
        return reservas.get(reservaId);
    }

    public void cambiarEstado(String reservaId, EstadoReserva estado) {
        Reserva r = reservas.get(reservaId);
        reservas.put(reservaId, new Reserva(r.reservaId(), r.clienteId(), r.eventoId(), estado,
                r.expiraEn(), r.detalles()));
    }

    public void rechazarConfirmacion(String codigo) {
        this.rechazoDeConfirmacion = codigo;
    }

    public void setCaido(boolean caido) {
        this.caido = caido;
    }

    /** Cantidad de pedidos de confirmación recibidos (exitosos o no). */
    public int confirmaciones() { return confirmaciones.get(); }

    public int liberaciones() { return liberaciones.get(); }

    public void reiniciar() {
        reservas.clear();
        liberadasPorDevolucion.clear();
        pedidosDeLiberacionPorDevolucion.set(0);
        confirmaciones.set(0);
        liberaciones.set(0);
        caido = false;
        filaNoNumerica = false;
        rechazoDeConfirmacion = null;
    }

    /** Simula que Reservas responde un lugar con fila no numérica (como lo informa el cliente REST). */
    private volatile boolean filaNoNumerica;

    public void setFilaNoNumerica(boolean v) { this.filaNoNumerica = v; }

    @Override
    public Optional<Reserva> obtenerReserva(String reservaId) {
        verificarDisponible();
        if (filaNoNumerica) {
            throw new ticketya.cliente.ContratoInvalidoException("Reservas",
                    "Reservas respondió datos con un formato inesperado en obtenerReserva (doble)");
        }
        return Optional.ofNullable(reservas.get(reservaId));
    }

    @Override
    public synchronized void confirmarVenta(String reservaId) {
        verificarDisponible();
        confirmaciones.incrementAndGet();
        if (rechazoDeConfirmacion != null) {
            throw new ConfirmacionRechazadaException(rechazoDeConfirmacion);
        }
        Reserva r = reservas.get(reservaId);
        if (r.estado() == EstadoReserva.LIBERADA) {
            throw new ConfirmacionRechazadaException("RESERVA_LIBERADA");
        }
        if (r.estado() == EstadoReserva.VIGENTE) {
            cambiarEstado(reservaId, EstadoReserva.CONFIRMADA);
        }
    }

    @Override
    public synchronized void liberarPorPagoRechazado(String reservaId) {
        verificarDisponible();
        liberaciones.incrementAndGet();
        cambiarEstado(reservaId, EstadoReserva.LIBERADA);
    }

    private final Map<String, LugarDevuelto> liberadasPorDevolucion = new ConcurrentHashMap<>();
    private final AtomicInteger pedidosDeLiberacionPorDevolucion = new AtomicInteger();

    /** Repetible por devolucionId: el mismo devolucionId no libera dos veces; con otro lugar da conflicto. */
    @Override
    public synchronized void liberarPorDevolucion(String reservaId, String devolucionId, LugarDevuelto lugar) {
        verificarDisponible();
        pedidosDeLiberacionPorDevolucion.incrementAndGet();
        LugarDevuelto anterior = liberadasPorDevolucion.putIfAbsent(devolucionId, lugar);
        if (anterior != null && !anterior.equals(lugar)) {
            throw new ServicioExternoException("Reservas", "Mismo devolucionId con otro lugar (409, doble)");
        }
    }

    /** Lugares liberados por devolución, uno por devolucionId. */
    public Map<String, LugarDevuelto> liberadasPorDevolucion() { return liberadasPorDevolucion; }

    public int pedidosDeLiberacionPorDevolucion() { return pedidosDeLiberacionPorDevolucion.get(); }

    private void verificarDisponible() {
        if (caido) {
            throw new ServicioExternoException("Reservas", "Reservas no responde (doble)");
        }
    }
}
