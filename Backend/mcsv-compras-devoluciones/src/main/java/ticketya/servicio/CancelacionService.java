package ticketya.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ticketya.cliente.EntradasCliente;
import ticketya.cliente.EntradasCliente.EstadoEntrada;
import ticketya.cliente.PagosCliente;
import ticketya.cliente.PagosCliente.EstadoPago;
import ticketya.cliente.PagosCliente.Pago;
import ticketya.cliente.ServicioExternoException;
import ticketya.error.ApiException;
import ticketya.modelo.CancelacionEvento;
import ticketya.modelo.Compra;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EntradaDeReintegro;
import ticketya.modelo.EstadoCancelacion;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.EstadoSolicitud;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;
import ticketya.modelo.SolicitudDevolucion;
import ticketya.repositorio.CancelacionEventoRepository;
import ticketya.repositorio.CompraRepository;
import ticketya.repositorio.EntradaDeReintegroRepository;
import ticketya.repositorio.ReintegroRepository;
import ticketya.repositorio.SolicitudDevolucionRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Cancelación de un evento (E-1): recibe el aviso de Eventos de forma idempotente por
 * eventoId y, en segundo plano, devuelve lo pagado por las entradas vigentes de cada
 * compra confirmada del evento, sin aprobación de un administrador. Las compras pendientes
 * se cierran como fallidas y, si ya estaban cobradas, se reintegra el total.
 */
@Service
public class CancelacionService {

    private static final Logger log = LoggerFactory.getLogger(CancelacionService.class);

    static final String MOTIVO_REINTEGRO = "Evento cancelado";
    static final String MOTIVO_CIERRE_SOLICITUD = "Evento cancelado: reintegro automático";
    static final String RESUELTA_POR = "sistema";

    private final CancelacionEventoRepository cancelaciones;
    private final CompraRepository compras;
    private final SolicitudDevolucionRepository solicitudes;
    private final ReintegroRepository reintegros;
    private final EntradaDeReintegroRepository entradasDeReintegro;
    private final ReintegroService reintegroService;
    private final EntradasCliente entradas;
    private final PagosCliente pagos;
    private final TransactionTemplate transaccion;
    private final TaskExecutor ejecutor;
    private final Duration plazoEnProceso;

    public CancelacionService(CancelacionEventoRepository cancelaciones, CompraRepository compras,
                              SolicitudDevolucionRepository solicitudes, ReintegroRepository reintegros,
                              EntradaDeReintegroRepository entradasDeReintegro, ReintegroService reintegroService,
                              EntradasCliente entradas, PagosCliente pagos,
                              TransactionTemplate transaccion,
                              @Qualifier("applicationTaskExecutor") TaskExecutor ejecutor,
                              @Value("${compras.cancelacion.plazo-en-proceso:5m}") Duration plazoEnProceso) {
        this.cancelaciones = cancelaciones;
        this.compras = compras;
        this.solicitudes = solicitudes;
        this.reintegros = reintegros;
        this.entradasDeReintegro = entradasDeReintegro;
        this.reintegroService = reintegroService;
        this.entradas = entradas;
        this.pagos = pagos;
        this.transaccion = transaccion;
        this.ejecutor = ejecutor;
        this.plazoEnProceso = plazoEnProceso;
    }

    /** Respuesta al aviso: aceptado (202) o ya procesado (200). */
    public enum ResultadoAviso { ACEPTADO, YA_PROCESADO }

    /**
     * Registra el aviso (se guarda antes de responder 202) y lanza el procesamiento en
     * segundo plano. Un aviso repetido no lanza otro procesamiento, salvo que la cancelación
     * haya quedado en proceso más allá del plazo: entonces la retoma si gana la toma atómica
     * a la reanudación automática, que de todos modos la retomaría sin ese aviso.
     */
    public ResultadoAviso recibir(String eventoId, String motivo, Instant canceladoEn) {
        try {
            cancelaciones.saveAndFlush(new CancelacionEvento(eventoId, motivo, canceladoEn));
            lanzar(eventoId);
            return ResultadoAviso.ACEPTADO;
        } catch (DataIntegrityViolationException yaRecibido) {
            CancelacionEvento c = cancelaciones.findById(eventoId).orElseThrow(() -> yaRecibido);
            if (c.getEstado() == EstadoCancelacion.PROCESADA) {
                return ResultadoAviso.YA_PROCESADO;
            }
            tomarSiVencida(c, "aviso repetido");
            return ResultadoAviso.ACEPTADO;
        }
    }

    /**
     * Reanudación automática: retoma las cancelaciones en proceso tomadas hace más del plazo
     * (el servicio se cayó o una compra falló), sin esperar otro aviso de Eventos, que ya no
     * va a llegar. Usa la misma toma atómica que el aviso repetido. Un error al tomar una no
     * frena a las demás.
     *
     * @return cuántas cancelaciones tomó este llamado
     */
    public int reanudarVencidas() {
        Instant limite = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(plazoEnProceso);
        int tomadas = 0;
        for (CancelacionEvento c : cancelaciones.findByEstadoAndTomadaEnBefore(EstadoCancelacion.EN_PROCESO, limite)) {
            try {
                if (tomarSiVencida(c, "reanudación automática")) {
                    tomadas++;
                }
            } catch (RuntimeException e) {
                log.error("No se pudo reanudar la cancelación del evento {}", c.getEventoId(), e);
            }
        }
        return tomadas;
    }

    /**
     * Toma una cancelación en proceso vencida (tomada hace más del plazo) y lanza su
     * procesamiento. La toma es un cambio condicional sobre el tomadaEn leído: entre dos
     * instancias, o entre la reanudación automática y un aviso repetido, solo uno la toma.
     *
     * @return true si este llamado la tomó
     */
    boolean tomarSiVencida(CancelacionEvento c, String origen) {
        if (c.getEstado() != EstadoCancelacion.EN_PROCESO) {
            return false;
        }
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        boolean vencida = c.getTomadaEn().plus(plazoEnProceso).isBefore(ahora);
        if (!vencida || cancelaciones.retomar(c.getEventoId(), c.getTomadaEn(), ahora) != 1) {
            return false;
        }
        log.info("Se retoma la cancelación del evento {} ({}), en proceso desde {}",
                c.getEventoId(), origen, c.getTomadaEn());
        lanzar(c.getEventoId());
        return true;
    }

    private void lanzar(String eventoId) {
        ejecutor.execute(() -> procesar(eventoId));
    }

    /**
     * Marca las entradas del evento en Entradas (solo informativo) y procesa cada compra
     * pendiente y confirmada por separado. Si una compra falla por algo inesperado, se sigue con las
     * demás y la cancelación queda en proceso: la reanudación automática la retoma pasado el
     * plazo. La marcación se pide solo si no se hizo antes con éxito.
     */
    public void procesar(String eventoId) {
        // La marcación se pide hasta que salga bien; al retomar no se repite si ya se hizo
        boolean marcacionHecha = cancelaciones.findById(eventoId).map(CancelacionEvento::isMarcacionHecha).orElse(false);
        if (!marcacionHecha) {
            try {
                int marcadas = entradas.marcarEntradasDelEvento(eventoId);
                cancelaciones.registrarMarcacion(eventoId, marcadas);
            } catch (ServicioExternoException e) {
                // Cada entrada se consulta y se pasa a devuelta de a una con el reintegro exitoso
                log.warn("Entradas no marcó las entradas del evento {}: {}", eventoId, e.getMessage());
                cancelaciones.registrarFallaDeMarcacion(eventoId,
                        CancelacionEvento.recortar("MARCACION: " + e.getMessage()));
            }
        }

        // 1. Compras cerradas por un procesamiento anterior cuyo reintegro quedó sin pedir: se
        //    vuelve a pedir con el mismo refundRequestId. Va primero para no repetir en esta
        //    misma corrida los cierres de abajo. Las demás fallidas no se tocan
        boolean todasProcesadas = procesarCada(eventoId, EstadoCompra.FALLIDA, this::retomarReintegroDeCierre);
        // 2. Las pendientes: una que un pago confirma mientras tanto se procesa como confirmada
        todasProcesadas &= procesarCada(eventoId, EstadoCompra.PENDIENTE, this::procesarPendiente);
        // 3. Se vuelven a buscar las confirmadas, incluidas las que se confirmaron antes de tocarlas
        todasProcesadas &= procesarCada(eventoId, EstadoCompra.CONFIRMADA, this::procesarCompra);
        if (todasProcesadas) {
            cancelaciones.terminar(eventoId);
        }
    }

    /**
     * Procesa cada compra del evento en ese estado por separado. Si una falla por algo
     * inesperado, se registra el error, se sigue con las demás y devuelve false.
     */
    private boolean procesarCada(String eventoId, EstadoCompra estado, java.util.function.Consumer<UUID> accion) {
        boolean todas = true;
        for (Compra compra : compras.findByEventoIdAndEstadoOrderByFechaAsc(eventoId, estado)) {
            try {
                accion.accept(compra.getId());
            } catch (RuntimeException e) {
                todas = false;
                log.error("No se pudo procesar la compra {} del evento cancelado {}", compra.getId(), eventoId, e);
                cancelaciones.registrarError(eventoId,
                        CancelacionEvento.recortar("COMPRA " + compra.getId() + ": " + e.getMessage()));
            }
        }
        return todas;
    }

    /**
     * Una compra confirmada: primero, con la compra y sus solicitudes bloqueadas, cierra las
     * solicitudes pendientes y registra el reintegro (si no lo tenía) con sus entradas; eso
     * se guarda antes de llamar a Pagos, así un procesamiento retomado reutiliza la misma
     * clave y nunca duplica. Después pide el reintegro y, si sale bien, devuelve las entradas.
     */
    void procesarCompra(UUID compraId) {
        UUID reintegroId = transaccion.execute(estado -> prepararReintegro(compraId));
        if (reintegroId != null) {
            completar(reintegroId, false);
        }
    }

    private UUID prepararReintegro(UUID compraId) {
        Compra compra = compras.bloquear(compraId).orElse(null);
        if (compra == null || compra.getEstado() != EstadoCompra.CONFIRMADA) {
            return null;
        }

        // Solicitudes de la compra: las pendientes se cierran; las aprobadas ya tienen su reintegro
        Set<String> conDevolucionAprobada = new HashSet<>();
        Instant ahora = Instant.now();
        for (SolicitudDevolucion s : solicitudes.bloquearDeLaCompra(compraId)) {
            if (s.getEstado() == EstadoSolicitud.PENDIENTE) {
                solicitudes.rechazar(s.getId(), MOTIVO_CIERRE_SOLICITUD, RESUELTA_POR, ahora);
            } else if (s.getEstado() == EstadoSolicitud.APROBADA) {
                conDevolucionAprobada.add(s.getEntradaId());
            }
        }

        // Un solo reintegro por cancelación por compra: si ya existe, se retoma ese
        List<Reintegro> existentes = reintegros.findByCompraIdAndOrigen(compraId, OrigenReintegro.CANCELACION_EVENTO);
        if (!existentes.isEmpty()) {
            return existentes.get(0).getId();
        }

        List<DetalleCompra> vigentes = compras.buscarConDetalle(compraId).orElseThrow().getDetalle().stream()
                .filter(d -> d.getEntradaId() != null && !conDevolucionAprobada.contains(d.getEntradaId()))
                .toList();
        if (vigentes.isEmpty()) {
            return null;
        }
        BigDecimal monto = vigentes.stream().map(DetalleCompra::getPrecioUnitario)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Reintegro r = reintegroService.registrar(compraId, UUID.randomUUID(), monto, MOTIVO_REINTEGRO,
                OrigenReintegro.CANCELACION_EVENTO);
        vigentes.forEach(d -> entradasDeReintegro.save(new EntradaDeReintegro(r.getId(), d.getEntradaId())));
        entradasDeReintegro.flush();
        return r.getId();
    }

    /** Pide el reintegro por cancelación de una compra fallida si quedó pendiente; los fallidos esperan a un administrador. */
    void retomarReintegroDeCierre(UUID compraId) {
        for (Reintegro r : reintegros.findByCompraIdAndOrigen(compraId, OrigenReintegro.CANCELACION_EVENTO)) {
            if (r.getEstado() == EstadoReintegro.PENDIENTE) {
                completar(r.getId(), false);
            }
        }
    }

    /** Resultado de cerrar una compra pendiente: sigue por el camino de confirmadas o tiene un reintegro que pedir. */
    private record Cierre(boolean confirmada, UUID reintegroId) {
        static final Cierre NADA = new Cierre(false, null);
        static final Cierre CONFIRMADA = new Cierre(true, null);
    }

    /**
     * Una compra pendiente del evento cancelado. Si mientras tanto un pago la confirmó, se
     * procesa como confirmada; si se cerró con un reintegro, se pide a Pagos después de
     * guardar el cierre, fuera de la transacción.
     */
    void procesarPendiente(UUID compraId) {
        Cierre cierre = transaccion.execute(estado -> cerrarPendiente(compraId));
        if (cierre.confirmada()) {
            procesarCompra(compraId);
        } else if (cierre.reintegroId() != null) {
            completar(cierre.reintegroId(), false);
        }
    }

    /**
     * Con la compra bloqueada (igual que el pago, así nunca avanzan los dos a la vez), la pasa
     * de pendiente a fallida con el cambio condicional. Solo si ese cambio se aplica y la
     * compra tiene un pago aprobado, registra un único reintegro por el total, con sus
     * entradas conocidas; se guarda antes de llamar a Pagos para que un procesamiento
     * retomado reutilice el mismo refundRequestId.
     */
    private Cierre cerrarPendiente(UUID compraId) {
        Compra compra = compras.bloquear(compraId).orElse(null);
        if (compra == null) {
            return Cierre.NADA;
        }
        if (compra.getEstado() == EstadoCompra.CONFIRMADA) {
            return Cierre.CONFIRMADA;
        }
        if (compra.getEstado() != EstadoCompra.PENDIENTE) {
            return Cierre.NADA;
        }
        String pagoId = compra.getPagoId();
        if (pagoId == null) {
            // El cobro pudo hacerse sin que llegara la respuesta: solo cuenta un pago aprobado.
            // Si Pagos no responde, la excepción deja la compra pendiente y la cancelación en
            // proceso, para retomarla: no se cierra a ciegas
            pagoId = pagos.listarPagosPorCompra(compraId.toString()).stream()
                    .filter(p -> p.status() == EstadoPago.APROBADO)
                    .map(Pago::paymentId)
                    .findFirst().orElse(null);
            if (pagoId != null) {
                compra.setPagoId(pagoId);
                compras.saveAndFlush(compra);
            }
        }
        BigDecimal total = compra.getTotal();
        if (compras.cambiarEstado(compraId, EstadoCompra.PENDIENTE, EstadoCompra.FALLIDA) != 1) {
            return Cierre.NADA;
        }
        log.info("Compra pendiente {} cerrada por la cancelación del evento ({})", compraId,
                pagoId == null ? "sin cobro" : "con el pago " + pagoId);
        if (pagoId == null) {
            return Cierre.NADA;
        }
        Reintegro r = reintegroService.registrar(compraId, UUID.randomUUID(), total, MOTIVO_REINTEGRO,
                OrigenReintegro.CANCELACION_EVENTO);
        // Las entradas emitidas cuya respuesta se perdió no tienen entradaId: quedan en reintegro
        compras.buscarConDetalle(compraId).orElseThrow().getDetalle().stream()
                .filter(d -> d.getEntradaId() != null)
                .forEach(d -> entradasDeReintegro.save(new EntradaDeReintegro(r.getId(), d.getEntradaId())));
        entradasDeReintegro.flush();
        return new Cierre(false, r.getId());
    }

    /**
     * Pide (o reintenta) el reintegro por cancelación con su fila bloqueada y, si queda
     * exitoso, pasa a devuelta las entradas incluidas que falten. En el procesamiento
     * automático solo se pide un reintegro pendiente; uno fallido lo reintenta un
     * administrador (reintentar = true), con el reintento de Pagos (/reintentar).
     *
     * @return true si el reintegro quedó exitoso y todas sus entradas devueltas
     */
    boolean completar(UUID reintegroId, boolean reintentar) {
        return Boolean.TRUE.equals(transaccion.execute(estado -> {
            Reintegro r = reintegros.bloquear(reintegroId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                    "REINTEGRO_NO_ENCONTRADO", "No existe el reintegro " + reintegroId));
            String pagoId = reintegroService.pagoDeLaCompra(r);
            if (reintentar) {
                r = reintegroService.reintentar(r, pagoId);
            } else if (r.getEstado() == EstadoReintegro.PENDIENTE) {
                r = reintegroService.pedir(r, pagoId);
            }
            if (r.getEstado() != EstadoReintegro.EXITOSO) {
                return false;
            }
            try {
                for (EntradaDeReintegro e : entradasDeReintegro.findByReintegroIdAndDevueltaFalseOrderByIdAsc(r.getId())) {
                    if (!yaDevuelta(e.getEntradaId())) {
                        entradas.pasarADevuelta(e.getEntradaId());
                    }
                    e.setDevuelta(true);
                    entradasDeReintegro.saveAndFlush(e);
                }
                if (r.getUltimoError() != null && r.getUltimoError().startsWith("ENTRADA_DEVUELTA")) {
                    r.setUltimoError(null);
                    reintegros.saveAndFlush(r);
                }
                return true;
            } catch (ServicioExternoException e) {
                log.warn("Reintegro {} exitoso con entradas sin devolver: {}", r.getId(), e.getMessage());
                r.setUltimoError("ENTRADA_DEVUELTA: " + e.getMessage());
                reintegros.saveAndFlush(r);
                return false;
            }
        }));
    }

    /** Reintento de un administrador (POST /reintegros/{id}/reintento). */
    public boolean reintentar(UUID reintegroId) {
        return completar(reintegroId, true);
    }

    /** Consulta la entrada antes de cambiarla, como en devoluciones. */
    private boolean yaDevuelta(String entradaId) {
        return entradas.consultar(entradaId)
                .orElseThrow(() -> new ServicioExternoException("Entradas",
                        "Entradas no encontró la entrada " + entradaId))
                .estado() == EstadoEntrada.DEVUELTA;
    }
}
