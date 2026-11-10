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

    public ReintegroService(ReintegroRepository reintegros, CompraRepository compras, PagosCliente pagos,
                            TransactionTemplate transaccion) {
        this.reintegros = reintegros;
        this.compras = compras;
        this.pagos = pagos;
        this.transaccion = transaccion;
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
     */
    public Reintegro pedir(Reintegro r, String pagoId) {
        String clave = r.getRefundRequestId().toString();
        try {
            aplicar(r, pagos.reintegrar(pagoId, clave, clave, r.getMonto(), r.getMotivo()));
        } catch (ConflictoEnPagosException | ServicioExternoException e) {
            log.warn("Reintegro {} sin resolver al pedirlo: {}", r.getId(), e.getMessage());
            r.setUltimoError(e.getMessage());
        }
        return reintegros.saveAndFlush(r);
    }

    /**
     * Reintenta sin duplicar: exitoso no llama a Pagos; pendiente sin respuesta repite el
     * pedido con la misma clave; fallido usa el reintento de Pagos (/reintentar) y, si responde 409 (ya no
     * está fallido), consulta el estado del reintegro.
     */
    public Reintegro reintentar(Reintegro r, String pagoId) {
        if (r.getEstado() == EstadoReintegro.EXITOSO) {
            return r;
        }
        if (r.getPagosReintegroId() == null) {
            return pedir(r, pagoId);
        }
        try {
            aplicar(r, pagos.reintentarReintegro(r.getPagosReintegroId()));
        } catch (ConflictoEnPagosException e) {
            try {
                pagos.consultarReintegro(r.getPagosReintegroId()).ifPresentOrElse(
                        actual -> aplicar(r, actual),
                        () -> r.setUltimoError("Pagos rechazó el reintento y no informa el reintegro"));
            } catch (ServicioExternoException sinRespuesta) {
                r.setUltimoError(sinRespuesta.getMessage());
            }
        } catch (ServicioExternoException e) {
            r.setUltimoError(e.getMessage());
        }
        return reintegros.saveAndFlush(r);
    }

    private void aplicar(Reintegro r, PagosCliente.Reintegro respuesta) {
        r.setPagosReintegroId(respuesta.id());
        r.setIntentos(respuesta.attemptCount() != null ? respuesta.attemptCount() : r.getIntentos() + 1);
        if (respuesta.status() == EstadoReintegroPagos.EXITOSO) {
            r.setEstado(EstadoReintegro.EXITOSO);
            r.setUltimoError(null);
        } else {
            r.setEstado(EstadoReintegro.FALLIDO);
            r.setUltimoError("Pagos no logró el reintegro");
        }
    }
}
