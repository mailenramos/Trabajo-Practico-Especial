package ticketya.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ticketya.error.ApiException;
import ticketya.repositorio.CompraRepository;
import ticketya.cliente.PagosCliente;
import ticketya.cliente.PagosCliente.ConflictoEnPagosException;
import ticketya.cliente.PagosCliente.EstadoReintegroPagos;
import ticketya.cliente.ServicioExternoException;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;
import ticketya.repositorio.ReintegroRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Reintegros pedidos a Pagos. La Idempotency-Key es el mismo valor que el refundRequestId,
 * así que volver a pedir un reintegro sin respuesta nunca lo duplica. Un reintegro
 * fallido se reintenta solo con el reintento de Pagos (/reintentar) sobre el mismo reintegro.
 *
 * Los métodos trabajan sobre la fila que reciben y la guardan; quien llama decide la
 * transacción y el bloqueo.
 */
@Service
public class ReintegroService {

    private static final Logger log = LoggerFactory.getLogger(ReintegroService.class);

    private final ReintegroRepository reintegros;
    private final CompraRepository compras;
    private final PagosCliente pagos;
    private final TransactionTemplate transaccion;
    private final AvisoService avisos;

    public ReintegroService(ReintegroRepository reintegros, CompraRepository compras, PagosCliente pagos,
                            TransactionTemplate transaccion, AvisoService avisos) {
        this.reintegros = reintegros;
        this.compras = compras;
        this.pagos = pagos;
        this.transaccion = transaccion;
        this.avisos = avisos;
    }

    /** Los reintegros, de todos los clientes, opcionalmente de un estado. */
    @Transactional(readOnly = true)
    public List<Reintegro> listar(EstadoReintegro estado) {
        return estado == null ? reintegros.findAllByOrderByCreadoEnDesc()
                : reintegros.findByEstadoOrderByCreadoEnDesc(estado);
    }

    /** Los reintegros pedidos sobre una compra, en el orden en que se registraron. */
    public List<Reintegro> deLaCompra(UUID compraId) {
        return reintegros.findByCompraIdOrderByCreadoEnAsc(compraId);
    }

    public Reintegro obtener(UUID reintegroId) {
        return reintegros.findById(reintegroId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REINTEGRO_NO_ENCONTRADO",
                        "No existe el reintegro " + reintegroId));
    }

    /**
     * Reintenta un reintegro con su fila bloqueada, así dos reintentos del mismo no
     * avanzan a la vez. Usa el pago de su compra.
     */
    public Reintegro reintentar(UUID reintegroId) {
        return transaccion.execute(estado -> {
            Reintegro r = reintegros.bloquear(reintegroId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REINTEGRO_NO_ENCONTRADO",
                            "No existe el reintegro " + reintegroId));
            return reintentar(r, pagoDeLaCompra(r));
        });
    }

    /** El pagoId guardado en la compra del reintegro. */
    public String pagoDeLaCompra(Reintegro r) {
        String pagoId = compras.findById(r.getCompraId()).map(c -> c.getPagoId()).orElse(null);
        if (pagoId == null) {
            throw new ApiException(HttpStatus.CONFLICT, "REINTEGRO_SIN_PAGO",
                    "La compra " + r.getCompraId() + " del reintegro no tiene un pago aprobado");
        }
        return pagoId;
    }

    /** Registra el reintegro como pendiente, antes de pedirlo. El motivo es obligatorio. */
    public Reintegro registrar(UUID compraId, UUID refundRequestId, BigDecimal monto, String motivo,
                               OrigenReintegro origen) {
        return reintegros.saveAndFlush(new Reintegro(compraId, refundRequestId, monto, motivo, origen));
    }

    /**
     * Pide el reintegro a Pagos sobre el pago de la compra. Con respuesta queda EXITOSO o
     * FALLIDO. Sin respuesta, o si Pagos lo rechaza al pedirlo (409/400), queda PENDIENTE
     * con el último error, y se puede volver a pedir con la misma clave.
     * <p>
     * La llamada a Pagos se hace fuera de la transacción que abre este método: la transacción
     * cubre solo guardar el resultado y su aviso (ver {@link #guardarResultado}).
     */
    public Reintegro pedir(Reintegro r, String pagoId) {
        String clave = r.getRefundRequestId().toString();
        PagosCliente.Reintegro respuesta = null;
        String error = null;
        try {
            respuesta = pagos.reintegrar(pagoId, clave, clave, r.getMonto(), r.getMotivo());
        } catch (ConflictoEnPagosException | ServicioExternoException e) {
            log.warn("Reintegro {} sin resolver al pedirlo: {}", r.getId(), e.getMessage());
            error = e.getMessage();
        }
        return guardarResultado(r, respuesta, error);
    }

    /**
     * Reintenta sin duplicar: exitoso no llama a Pagos; pendiente sin respuesta repite el
     * pedido con la misma clave; fallido usa el reintento de Pagos (/reintentar) y, si responde 409 (ya no
     * está fallido), consulta el estado del reintegro. Igual que {@link #pedir}, las llamadas a Pagos
     * van fuera de la transacción que guarda el resultado.
     */
    public Reintegro reintentar(Reintegro r, String pagoId) {
        if (r.getEstado() == EstadoReintegro.EXITOSO) {
            return r;
        }
        if (r.getPagosReintegroId() == null) {
            return pedir(r, pagoId);
        }
        PagosCliente.Reintegro respuesta = null;
        String error = null;
        try {
            respuesta = pagos.reintentarReintegro(r.getPagosReintegroId());
        } catch (ConflictoEnPagosException e) {
            try {
                respuesta = pagos.consultarReintegro(r.getPagosReintegroId()).orElse(null);
                if (respuesta == null) {
                    error = "Pagos rechazó el reintento y no informa el reintegro";
                }
            } catch (ServicioExternoException sinRespuesta) {
                error = sinRespuesta.getMessage();
            }
        } catch (ServicioExternoException e) {
            error = e.getMessage();
        }
        return guardarResultado(r, respuesta, error);
    }

    /**
     * Guarda en una transacción el resultado de Pagos (o el error) y, si el estado cambió, el aviso
     * de resultado del reintegro: los dos quedan juntos o no queda ninguno. Si quien llama ya tiene
     * una transacción, se suma a ella (REQUIRED); si no, abre una corta, sin llamadas externas.
     */
    private Reintegro guardarResultado(Reintegro r, PagosCliente.Reintegro respuesta, String error) {
        return transaccion.execute(estado -> {
            if (respuesta != null) {
                aplicar(r, respuesta);
            } else {
                r.setUltimoError(error);
            }
            return reintegros.saveAndFlush(r);
        });
    }

    /**
     * Aplica la respuesta de Pagos. Si el estado cambia (a EXITOSO o FALLIDO), guarda el aviso de
     * resultado del reintegro. Se llama solo desde {@link #guardarResultado}, dentro de su transacción.
     */
    private void aplicar(Reintegro r, PagosCliente.Reintegro respuesta) {
        EstadoReintegro anterior = r.getEstado();
        r.setPagosReintegroId(respuesta.id());
        r.setIntentos(respuesta.attemptCount() != null ? respuesta.attemptCount() : r.getIntentos() + 1);
        if (respuesta.status() == EstadoReintegroPagos.EXITOSO) {
            r.setEstado(EstadoReintegro.EXITOSO);
            r.setUltimoError(null);
        } else {
            r.setEstado(EstadoReintegro.FALLIDO);
            r.setUltimoError("Pagos no logró el reintegro");
        }
        if (r.getEstado() != anterior) {
            avisos.resultadoReintegro(r);
        }
    }
}
