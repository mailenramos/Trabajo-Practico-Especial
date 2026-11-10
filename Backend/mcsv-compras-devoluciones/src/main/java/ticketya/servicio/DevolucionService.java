package ticketya.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import ticketya.cliente.ReservasCliente.LugarDevuelto;
import ticketya.cliente.ServicioExternoException;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.Reintegro;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ticketya.cliente.ReservasCliente;
import ticketya.modelo.OrigenReintegro;
import ticketya.repositorio.CompraRepository;
import ticketya.cliente.EntradasCliente;
import ticketya.cliente.EntradasCliente.Entrada;
import ticketya.cliente.EntradasCliente.EstadoEntrada;
import ticketya.cliente.EventosCliente;
import ticketya.cliente.EventosCliente.Evento;
import ticketya.error.ApiException;
import ticketya.modelo.Compra;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.EstadoSolicitud;
import ticketya.modelo.SolicitudDevolucion;
import ticketya.repositorio.DetalleCompraRepository;
import ticketya.repositorio.ReintegroRepository;
import ticketya.repositorio.SolicitudDevolucionRepository;
import ticketya.web.UsuarioActual;
import ticketya.web.dto.DevolucionRespuesta;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Solicitudes de devolución de entradas (D-1 a D-3). */
@Service
public class DevolucionService {

    private static final Logger log = LoggerFactory.getLogger(DevolucionService.class);

    /** Una entrada se puede devolver solo si el evento empieza en más de este plazo. */
    static final Duration PLAZO_DEVOLUCION = Duration.ofHours(72);

    private final SolicitudDevolucionRepository solicitudes;
    private final DetalleCompraRepository detalles;
    private final ReintegroRepository reintegros;
    private final CompraRepository compras;
    private final ReintegroService reintegroService;
    private final EntradasCliente entradas;
    private final EventosCliente eventos;
    private final ReservasCliente reservas;
    private final TransactionTemplate transaccion;

    public DevolucionService(SolicitudDevolucionRepository solicitudes, DetalleCompraRepository detalles,
                             ReintegroRepository reintegros, CompraRepository compras,
                             ReintegroService reintegroService, EntradasCliente entradas,
                             EventosCliente eventos, ReservasCliente reservas, TransactionTemplate transaccion) {
        this.solicitudes = solicitudes;
        this.detalles = detalles;
        this.reintegros = reintegros;
        this.compras = compras;
        this.reintegroService = reintegroService;
        this.entradas = entradas;
        this.eventos = eventos;
        this.reservas = reservas;
        this.transaccion = transaccion;
    }

    /** Resultado de solicitar: una solicitud nueva (201) o la pendiente que ya existía (200). */
    public record InicioDevolucion(SolicitudDevolucion solicitud, boolean nueva) {
    }

    /**
     * D-1: valida de lo más barato a lo más caro (datos propios, Entradas, Eventos) y
     * registra la solicitud pendiente. Si Entradas o Eventos no responden, no se registra
     * nada y la falla se informa como 503.
     */
    public InicioDevolucion solicitar(UsuarioActual usuario, String entradaId) {
        // 1 y 2. La entrada es de una compra propia y confirmada
        DetalleCompra detalle = detalles.buscarPorEntrada(entradaId)
                .orElseThrow(() -> entradaNoEncontrada(entradaId));
        Compra compra = detalle.getCompra();
        if (!compra.getClienteId().equals(usuario.id())) {
            throw entradaAjena(entradaId);
        }
        if (compra.getEstado() != EstadoCompra.CONFIRMADA || compra.getPagoId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ENTRADA_NO_VIGENTE",
                    "La compra de la entrada " + entradaId + " no está confirmada");
        }

        // 3. Una sola solicitud activa por entrada
        var activa = solicitudes.findByEntradaActiva(entradaId);
        if (activa.isPresent()) {
            return solicitudExistente(activa.get());
        }

        // 4. Titular y estado según Entradas
        Entrada entrada = entradas.consultar(entradaId).orElseThrow(() -> entradaNoEncontrada(entradaId));
        if (!usuario.id().equals(entrada.idCliente())) {
            throw entradaAjena(entradaId);
        }
        if (entrada.estado() != EstadoEntrada.VIGENTE) {
            throw new ApiException(HttpStatus.CONFLICT, "ENTRADA_NO_VIGENTE",
                    "La entrada " + entradaId + " no está vigente (" + entrada.estado() + ")");
        }

        // 5. Evento no cancelado y que empiece en más de 72 horas
        Evento evento = eventos.obtenerEvento(compra.getEventoId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_GATEWAY, "EVENTO_NO_ENCONTRADO",
                        "El servicio de eventos no encontró el evento " + compra.getEventoId()));
        if (evento.estado() != null && evento.estado().equalsIgnoreCase("cancelado")) {
            throw new ApiException(HttpStatus.CONFLICT, "EVENTO_CANCELADO",
                    "El evento fue cancelado: el reintegro de sus entradas es automático");
        }
        if (!Duration.between(Instant.now(), evento.fechaInicio()).minus(PLAZO_DEVOLUCION).isPositive()) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAZO_VENCIDO",
                    "Solo se puede devolver una entrada hasta 72 horas antes del inicio del evento");
        }

        // 6. Registro; la restricción única decide entre pedidos simultáneos
        try {
            return new InicioDevolucion(solicitudes.saveAndFlush(
                    new SolicitudDevolucion(usuario.id(), compra, detalle)), true);
        } catch (DataIntegrityViolationException e) {
            SolicitudDevolucion ganadora = solicitudes.findByEntradaActiva(entradaId).orElseThrow(() -> e);
            return solicitudExistente(ganadora);
        }
    }

    /**
     * D-2: rechaza una solicitud pendiente con un motivo. No pide nada a otros servicios:
     * la entrada sigue vigente y puede volver a solicitarse.
     */
    public SolicitudDevolucion rechazar(UsuarioActual administrador, UUID devolucionId, String motivo) {
        buscar(devolucionId);
        if (solicitudes.rechazar(devolucionId, motivo.trim(), administrador.id(), Instant.now()) == 0) {
            throw yaResuelta(devolucionId);
        }
        return buscar(devolucionId);
    }

    /**
     * D-2: aprueba una solicitud pendiente. Con la fila bloqueada, el cambio condicional a
     * aprobada y el registro del reintegro van en la misma transacción: una solicitud
     * aprobada siempre tiene su reintegro y dos aprobaciones simultáneas no crean dos.
     * Después coordina la devolución con los otros servicios.
     */
    public SolicitudDevolucion aprobar(UsuarioActual administrador, UUID devolucionId) {
        transaccion.executeWithoutResult(estado -> {
            SolicitudDevolucion s = solicitudes.bloquear(devolucionId)
                    .orElseThrow(() -> noEncontrada(devolucionId));
            if (s.getEstado() != EstadoSolicitud.PENDIENTE
                    || solicitudes.aprobar(devolucionId, administrador.id(), Instant.now()) == 0) {
                throw yaResuelta(devolucionId);
            }
            reintegroService.registrar(s.getCompraId(), s.getId(), s.getMonto(),
                    "Devolución de entrada " + s.getEntradaId(), OrigenReintegro.DEVOLUCION);
        });
        return coordinar(devolucionId);
    }

    /**
     * Coordina una devolución aprobada, desde el primer paso sin hacer: entrada a en
     * reintegro, liberar el lugar, reintegro y, con el reintegro exitoso, entrada a devuelta.
     * Corre con la fila de la solicitud bloqueada. Si un paso falla por una falla técnica,
     * guarda el error y corta, sin revertir lo hecho; lo hecho queda guardado igual.
     * Cada paso es repetible sin duplicar efectos, así que retomar es seguro.
     */
    public SolicitudDevolucion coordinar(UUID devolucionId) {
        transaccion.executeWithoutResult(estado -> {
            SolicitudDevolucion s = solicitudes.bloquear(devolucionId).orElseThrow(() -> noEncontrada(devolucionId));
            if (s.getEstado() != EstadoSolicitud.APROBADA) {
                return;
            }
            Compra compra = compras.findById(s.getCompraId()).orElseThrow();
            Reintegro reintegro = reintegros.findByRefundRequestId(s.getId()).orElseThrow();
            String paso = "ENTRADA_EN_REINTEGRO";
            try {
                s.setUltimoError(null);
                if (!s.isEntradaEnReintegro()) {
                    if (!estadoDeLaEntradaEs(s.getEntradaId(), EstadoEntrada.EN_REINTEGRO, EstadoEntrada.DEVUELTA)) {
                        entradas.pasarAEnReintegro(s.getEntradaId());
                    }
                    s.setEntradaEnReintegro(true);
                }
                paso = "LIBERAR_LUGAR";
                if (!s.isLugarLiberado()) {
                    reservas.liberarPorDevolucion(compra.getReservaId(), s.getId().toString(),
                            new LugarDevuelto(s.getSectorId(), s.getFila(), s.getNumero()));
                    s.setLugarLiberado(true);
                }
                paso = "REINTEGRO";
                if (reintegro.getEstado() != EstadoReintegro.EXITOSO) {
                    // Pendiente: lo pide (o lo repite con la misma clave). Fallido: /retry de Pagos
                    reintegro = reintegroService.reintentar(reintegro, compra.getPagoId());
                }
                paso = "ENTRADA_DEVUELTA";
                if (reintegro.getEstado() == EstadoReintegro.EXITOSO && !s.isEntradaDevuelta()) {
                    if (!estadoDeLaEntradaEs(s.getEntradaId(), EstadoEntrada.DEVUELTA)) {
                        entradas.pasarADevuelta(s.getEntradaId());
                    }
                    s.setEntradaDevuelta(true);
                }
            } catch (ServicioExternoException e) {
                log.warn("Devolución {} incompleta en el paso {}: {}", devolucionId, paso, e.getMessage());
                s.setUltimoError(paso + ": " + e.getMessage());
            }
            solicitudes.saveAndFlush(s);
        });
        return buscar(devolucionId);
    }

    /** Consulta la entrada antes de cambiarla: si ya está en el estado buscado, el paso está hecho. */
    private boolean estadoDeLaEntradaEs(String entradaId, EstadoEntrada... estados) {
        Entrada e = entradas.consultar(entradaId).orElseThrow(() ->
                new ServicioExternoException("Entradas", "Entradas no encontró la entrada " + entradaId));
        return Arrays.asList(estados).contains(e.estado());
    }

    /** D-3: el cliente ve solo sus solicitudes; el administrador, todas. Opcionalmente de un estado. */
    public List<DevolucionRespuesta> listar(UsuarioActual usuario, boolean administrador, EstadoSolicitud estado) {
        List<SolicitudDevolucion> lista;
        if (administrador) {
            lista = estado == null ? solicitudes.findAllByOrderByCreadaEnDesc()
                    : solicitudes.findByEstadoOrderByCreadaEnDesc(estado);
        } else {
            lista = estado == null ? solicitudes.findByClienteIdOrderByCreadaEnDesc(usuario.id())
                    : solicitudes.findByClienteIdAndEstadoOrderByCreadaEnDesc(usuario.id(), estado);
        }
        return lista.stream().map(this::respuesta).toList();
    }

    /** D-3 y P-1: una solicitud, solo para su titular o un administrador. */
    public DevolucionRespuesta obtener(UsuarioActual usuario, boolean administrador, UUID devolucionId) {
        SolicitudDevolucion s = buscar(devolucionId);
        if (!administrador && !s.getClienteId().equals(usuario.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO",
                    "La devolución " + devolucionId + " pertenece a otro cliente");
        }
        return respuesta(s);
    }

    SolicitudDevolucion buscar(UUID devolucionId) {
        return solicitudes.findById(devolucionId).orElseThrow(() -> noEncontrada(devolucionId));
    }

    private static ApiException noEncontrada(UUID devolucionId) {
        return new ApiException(HttpStatus.NOT_FOUND, "DEVOLUCION_NO_ENCONTRADA",
                "No existe la devolución " + devolucionId);
    }

    private static ApiException yaResuelta(UUID devolucionId) {
        return new ApiException(HttpStatus.CONFLICT, "DEVOLUCION_YA_RESUELTA",
                "La devolución " + devolucionId + " ya fue aprobada o rechazada");
    }

    /** La solicitud con el estado de su reintegro (refundRequestId = devolucionId). */
    public DevolucionRespuesta respuesta(SolicitudDevolucion s) {
        return DevolucionRespuesta.de(s, reintegros.findByRefundRequestId(s.getId()).orElse(null));
    }

    /** La entrada ya tiene una solicitud activa: la pendiente se devuelve; la aprobada da 409. */
    private InicioDevolucion solicitudExistente(SolicitudDevolucion s) {
        if (s.getEstado() == EstadoSolicitud.PENDIENTE) {
            return new InicioDevolucion(s, false);
        }
        throw new ApiException(HttpStatus.CONFLICT, "DEVOLUCION_EN_CURSO",
                "La entrada " + s.getEntradaId() + " ya tiene la devolución aprobada " + s.getId());
    }

    private static ApiException entradaNoEncontrada(String entradaId) {
        return new ApiException(HttpStatus.NOT_FOUND, "ENTRADA_NO_ENCONTRADA", "No existe la entrada " + entradaId);
    }

    private static ApiException entradaAjena(String entradaId) {
        return new ApiException(HttpStatus.FORBIDDEN, "ENTRADA_AJENA",
                "La entrada " + entradaId + " pertenece a otro cliente");
    }
}
