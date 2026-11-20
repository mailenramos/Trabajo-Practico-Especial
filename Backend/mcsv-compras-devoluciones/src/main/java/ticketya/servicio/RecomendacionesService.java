package ticketya.servicio;

import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ticketya.error.ApiException;
import ticketya.repositorio.CantidadPorTipo;
import ticketya.repositorio.CoCompra;
import ticketya.repositorio.CompraRepository;

import java.util.List;

/** Consultas internas sobre las compras confirmadas que usa Recomendaciones (MS8). */
@Service
public class RecomendacionesService {

    static final int LIMITE_CO_COMPRAS_POR_DEFECTO = 10;
    static final int LIMITE_CO_COMPRAS_MAXIMO = 20;
    static final int LIMITE_EVENTOS_COMPRADOS = 20;

    private final CompraRepository compras;

    public RecomendacionesService(CompraRepository compras) {
        this.compras = compras;
    }

    /**
     * Los otros eventos comprados por los clientes con una compra confirmada del evento. Sin
     * límite se devuelven 10; un límite mayor a 20 se trata como 20 y uno menor a 1 es inválido.
     */
    @Transactional(readOnly = true)
    public List<CoCompra> coCompras(String eventoId, Integer limite) {
        return compras.coCompras(eventoId, Limit.of(limiteCoCompras(limite)));
    }

    /** Los tipos de evento de las compras confirmadas del cliente, con su cantidad (lista vacía si no tiene). */
    @Transactional(readOnly = true)
    public List<CantidadPorTipo> historialPorTipo(String usuarioId) {
        return compras.cantidadesPorTipo(usuarioId);
    }

    /** Los eventos distintos de las compras confirmadas del cliente, del más reciente al más antiguo; hasta 20. */
    @Transactional(readOnly = true)
    public List<String> eventosComprados(String usuarioId) {
        return compras.eventosComprados(usuarioId, Limit.of(LIMITE_EVENTOS_COMPRADOS));
    }

    static int limiteCoCompras(Integer pedido) {
        if (pedido == null) {
            return LIMITE_CO_COMPRAS_POR_DEFECTO;
        }
        if (pedido < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARAMETRO_INVALIDO",
                    "El parámetro limite debe ser un entero mayor a 0");
        }
        return Math.min(pedido, LIMITE_CO_COMPRAS_MAXIMO);
    }
}
