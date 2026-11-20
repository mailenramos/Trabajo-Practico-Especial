package ticketya.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ticketya.modelo.ModificacionEvento;
import ticketya.repositorio.CompraRepository;
import ticketya.repositorio.ModificacionEventoRepository;
import ticketya.repositorio.SolicitudDevolucionRepository;

import java.time.Instant;

/**
 * Modificación de un evento (fecha, horario o lugar) avisada por Eventos. Idempotente por
 * (evento, versión). Todo en una sola transacción: registrar la modificación, actualizar la fecha
 * de inicio guardada en las compras y solicitudes de devolución del evento (solo si cambió la
 * fecha) y guardar un aviso EVENTO_MODIFICADO por comprador. Si algo falla no queda nada, y Eventos
 * puede reintentar con la misma versión. No genera reintegros ni toca entradas.
 */
@Service
public class ModificacionEventoService {

    private static final Logger log = LoggerFactory.getLogger(ModificacionEventoService.class);

    /** Resultado: aceptada (202), o ya procesada / versión anterior (200, sin cambios). */
    public enum ResultadoModificacion { ACEPTADA, YA_PROCESADA, VERSION_ANTERIOR }

    private final ModificacionEventoRepository modificaciones;
    private final CompraRepository compras;
    private final SolicitudDevolucionRepository solicitudes;
    private final AvisoService avisos;
    private final TransactionTemplate transaccion;

    public ModificacionEventoService(ModificacionEventoRepository modificaciones, CompraRepository compras,
                                     SolicitudDevolucionRepository solicitudes, AvisoService avisos,
                                     TransactionTemplate transaccion) {
        this.modificaciones = modificaciones;
        this.compras = compras;
        this.solicitudes = solicitudes;
        this.avisos = avisos;
        this.transaccion = transaccion;
    }

    /**
     * Procesa la modificación. {@code fechaHora} puede ser nula (solo cambió el lugar); {@code lugar}
     * y {@code ciudad} también, pero quien llama garantiza que venga al menos uno de los tres.
     */
    public ResultadoModificacion recibir(String eventoId, int version, Instant fechaHora, String lugar, String ciudad) {
        try {
            return transaccion.execute(estado -> {
                Integer maxima = modificaciones.maximaVersion(eventoId).orElse(null);
                if (maxima != null && version < maxima) {
                    log.info("Modificación del evento {} versión {} ignorada: ya se procesó la versión {}",
                            eventoId, version, maxima);
                    return ResultadoModificacion.VERSION_ANTERIOR;
                }
                // La restricción única decide entre avisos repetidos o simultáneos de la misma versión
                modificaciones.saveAndFlush(new ModificacionEvento(eventoId, version));
                if (fechaHora != null) {
                    int enCompras = compras.actualizarFechaDelEvento(eventoId, fechaHora);
                    int enSolicitudes = solicitudes.actualizarFechaDelEvento(eventoId, fechaHora);
                    log.info("Evento {} versión {}: nueva fecha {} en {} compras y {} solicitudes de devolución",
                            eventoId, version, fechaHora, enCompras, enSolicitudes);
                }
                avisos.eventoModificado(eventoId, version, fechaHora, lugar, ciudad);
                return ResultadoModificacion.ACEPTADA;
            });
        } catch (DataIntegrityViolationException e) {
            // Solo es un aviso repetido si la versión quedó registrada; si no, es otro problema
            if (modificaciones.existsByEventoIdAndVersion(eventoId, version)) {
                return ResultadoModificacion.YA_PROCESADA;
            }
            throw e;
        }
    }
}
