package ticketya.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ticketya.modelo.Compra;
import ticketya.modelo.Reintegro;
import ticketya.modelo.SolicitudDevolucion;
import ticketya.modelo.TipoAviso;
import ticketya.repositorio.AvisoPendienteRepository;
import ticketya.repositorio.CompraRepository;
import ticketya.repositorio.CompradorDeEvento;
import ticketya.web.CorrelacionFilter;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Genera los avisos a Notificaciones (outbox). Cada método corre dentro de la transacción de la
 * operación de negocio que lo llama (MANDATORY): el aviso queda guardado junto con el cambio de
 * estado, o no queda nada. El envío lo hace después {@link EnvioDeAvisosService}.
 * <p>
 * El acontecimientoId es estable (tipo + id del recurso): repetir la operación no crea otro aviso.
 * Los campos de {@code datos} son los que propone el contrato de Notificaciones para cada tipo.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AvisoService {

    private static final Logger log = LoggerFactory.getLogger(AvisoService.class);
    static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final DateTimeFormatter HORARIO = DateTimeFormatter.ofPattern("HH:mm");

    private final AvisoPendienteRepository avisos;
    private final CompraRepository compras;
    private final JsonMapper json;
    private final ZoneId zona;

    public AvisoService(AvisoPendienteRepository avisos, CompraRepository compras, JsonMapper json,
                        @Value("${compras.zona-horaria:America/Argentina/Buenos_Aires}") String zona) {
        this.avisos = avisos;
        this.compras = compras;
        this.json = json;
        this.zona = ZoneId.of(zona);
    }

    /** Compra confirmada: COMPRA_CONFIRMADA y ENTRADAS_DISPONIBLES (las entradas ya se emitieron). */
    public void compraConfirmada(UUID compraId) {
        Compra c = compras.buscarConDetalle(compraId).orElseThrow();
        String id = compraId.toString();
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("evento", c.getEventoNombre());
        datos.put("fecha", fechaHora(c.getEventoFechaInicio()));
        datos.put("cantidad", c.getDetalle().size());
        datos.put("total", importe(c.getTotal()));
        registrar(TipoAviso.COMPRA_CONFIRMADA, "compra-confirmada-" + id, c.getClienteId(), id, datos);

        Map<String, Object> entradas = new LinkedHashMap<>();
        entradas.put("evento", c.getEventoNombre());
        entradas.put("fecha", fechaHora(c.getEventoFechaInicio()));
        registrar(TipoAviso.ENTRADAS_DISPONIBLES, "entradas-disponibles-" + id, c.getClienteId(), id, entradas);
    }

    public void devolucionAprobada(SolicitudDevolucion s) {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("evento", s.getEventoNombre());
        datos.put("monto", importe(s.getMonto()));
        String id = s.getId().toString();
        registrar(TipoAviso.DEVOLUCION_APROBADA, "devolucion-aprobada-" + id, s.getClienteId(), id, datos);
    }

    public void devolucionRechazada(SolicitudDevolucion s) {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("evento", s.getEventoNombre());
        datos.put("motivo", s.getMotivoRechazo());
        String id = s.getId().toString();
        registrar(TipoAviso.DEVOLUCION_RECHAZADA, "devolucion-rechazada-" + id, s.getClienteId(), id, datos);
    }

    /**
     * El reintegro terminó EXITOSO o FALLIDO. El resultado va en el acontecimientoId: un reintegro
     * fallido que después sale bien genera un segundo aviso, y repetir el mismo resultado no.
     */
    public void resultadoReintegro(Reintegro r) {
        Compra c = compras.findById(r.getCompraId()).orElse(null);
        if (c == null) {
            log.warn("El reintegro {} no tiene la compra {}: no se avisa su resultado", r.getId(), r.getCompraId());
            return;
        }
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("monto", importe(r.getMonto()));
        datos.put("resultado", r.getEstado().name());
        String id = r.getId().toString();
        registrar(TipoAviso.RESULTADO_REINTEGRO, "resultado-reintegro-" + id + "-" + r.getEstado().name(),
                c.getClienteId(), id, datos);
    }

    /**
     * Evento cancelado: un aviso por cliente con una compra CONFIRMADA del evento (un cliente con
     * varias compras recibe uno solo). Todos comparten el acontecimientoId.
     */
    public void eventoCancelado(String eventoId) {
        for (CompradorDeEvento comprador : compras.compradoresConfirmados(eventoId)) {
            Map<String, Object> datos = new LinkedHashMap<>();
            datos.put("evento", comprador.eventoNombre());
            datos.put("fecha", fechaHora(comprador.eventoFechaInicio()));
            registrar(TipoAviso.EVENTO_CANCELADO, "evento-cancelado-" + eventoId, comprador.clienteId(), eventoId, datos);
        }
    }

    /**
     * Evento cancelado para el comprador de una compra cobrada después del cierre del evento (la
     * emisión respondió EVENTO_CERRADO). Mismo acontecimientoId que la cancelación: si el cliente ya
     * recibió ese aviso por otra compra del evento, no se duplica.
     */
    public void eventoCanceladoParaCompra(Compra c) {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("evento", c.getEventoNombre());
        datos.put("fecha", fechaHora(c.getEventoFechaInicio()));
        registrar(TipoAviso.EVENTO_CANCELADO, "evento-cancelado-" + c.getEventoId(), c.getClienteId(),
                c.getEventoId(), datos);
    }

    /**
     * Evento modificado:un aviso EVENTO_MODIFICADO por cliente con una compra CONFIRMADA del evento,
     * todos con el acontecimientoId {@code evento-modificado-{eventoId}-v{version}}. La nueva fecha y
     * el nuevo horario (en la zona configurada) van solo si cambió la fecha; el nuevo lugar, solo si
     * vino lugar o ciudad.
     */
    public void eventoModificado(String eventoId, int version, Instant fechaHora, String lugar, String ciudad) {
        String nuevoLugar = java.util.stream.Stream.of(lugar, ciudad)
                .filter(v -> v != null && !v.isBlank()).map(String::trim)
                .collect(java.util.stream.Collectors.joining(", "));
        for (CompradorDeEvento comprador : compras.compradoresConfirmados(eventoId)) {
            Map<String, Object> datos = new LinkedHashMap<>();
            datos.put("evento", comprador.eventoNombre());
            if (fechaHora != null) {
                datos.put("nuevaFecha", FECHA.format(fechaHora.atZone(zona)));
                datos.put("nuevoHorario", HORARIO.format(fechaHora.atZone(zona)));
            }
            if (!nuevoLugar.isEmpty()) {
                datos.put("nuevoLugar", nuevoLugar);
            }
            registrar(TipoAviso.EVENTO_MODIFICADO, "evento-modificado-" + eventoId + "-v" + version,
                    comprador.clienteId(), eventoId, datos);
        }
    }

    /**
     * Guarda el aviso PENDIENTE; si ya existe (mismo acontecimiento, tipo y destinatario) no hace nada.
     * La correlación es la del pedido en curso (la del Gateway) o, si no hay, una nueva.
     */
    public void registrar(TipoAviso tipo, String acontecimientoId, String usuarioId, String recursoId,
                          Map<String, Object> datos) {
        String correlacion = CorrelacionFilter.actual();
        avisos.registrar(acontecimientoId, tipo.name(), usuarioId, recursoId,
                correlacion != null ? correlacion : UUID.randomUUID().toString(),
                json.writeValueAsString(datos), Instant.now());
    }

    String fechaHora(Instant instante) {
        return instante == null ? null : FECHA_HORA.format(instante.atZone(zona));
    }

    private static String importe(BigDecimal monto) {
        return monto == null ? null : monto.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
