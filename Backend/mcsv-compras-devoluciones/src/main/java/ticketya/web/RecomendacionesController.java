package ticketya.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ticketya.repositorio.CantidadPorTipo;
import ticketya.repositorio.CoCompra;
import ticketya.servicio.RecomendacionesService;

import java.util.List;

/**
 * Consultas internas para Recomendaciones (MS8). No pasan por el Gateway ni llevan encabezados
 * de usuario: exigen X-Service-Token ({@link AutorizacionDeServicioFilter}).
 */
@RestController
@RequestMapping("/internal")
public class RecomendacionesController {

    private final RecomendacionesService recomendaciones;

    public RecomendacionesController(RecomendacionesService recomendaciones) {
        this.recomendaciones = recomendaciones;
    }

    public record CoComprasRespuesta(String eventoId, List<CoCompra> coCompras) {
    }

    public record HistorialRespuesta(String usuarioId, List<CantidadPorTipo> tipos, List<String> eventosComprados) {
    }

    /** 200 siempre, con listas vacías si el cliente no tiene compras confirmadas. */
    @GetMapping("/usuarios/{usuarioId}/historial-preferencias")
    public HistorialRespuesta historial(@PathVariable String usuarioId) {
        return new HistorialRespuesta(usuarioId, recomendaciones.historialPorTipo(usuarioId),
                recomendaciones.eventosComprados(usuarioId));
    }

    /** 200 siempre, con la lista vacía si el evento no tiene compras confirmadas o co-compras. */
    @GetMapping("/eventos/{eventoId}/co-compras")
    public CoComprasRespuesta coCompras(@PathVariable String eventoId,
                                        @RequestParam(required = false) Integer limite) {
        return new CoComprasRespuesta(eventoId, recomendaciones.coCompras(eventoId, limite));
    }
}
