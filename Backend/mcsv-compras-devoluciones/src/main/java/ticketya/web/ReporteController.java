package ticketya.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ticketya.servicio.ReporteService;
import ticketya.web.dto.ReporteDevolucionesRespuesta;
import ticketya.web.dto.ReporteOcupacionRespuesta;
import ticketya.web.dto.ReporteRecaudacionRespuesta;
import ticketya.web.dto.ReporteVentasRespuesta;

/**
 * Reportes de un evento para su organizador (titular según Eventos) o un administrador. Son de
 * solo lectura: consultarlos no cambia nada y se pueden repetir.
 */
@RestController
@RequestMapping("/reportes/eventos/{eventoId}")
public class ReporteController {

    private final ReporteService reportes;

    public ReporteController(ReporteService reportes) {
        this.reportes = reportes;
    }

    @GetMapping("/ventas")
    public ReporteVentasRespuesta ventas(UsuarioActual usuario, @PathVariable String eventoId) {
        return reportes.ventas(usuario, eventoId);
    }

    @GetMapping("/devoluciones")
    public ReporteDevolucionesRespuesta devoluciones(UsuarioActual usuario, @PathVariable String eventoId) {
        return reportes.devoluciones(usuario, eventoId);
    }

    @GetMapping("/recaudacion")
    public ReporteRecaudacionRespuesta recaudacion(UsuarioActual usuario, @PathVariable String eventoId) {
        return reportes.recaudacion(usuario, eventoId);
    }

    @GetMapping("/ocupacion")
    public ReporteOcupacionRespuesta ocupacion(UsuarioActual usuario, @PathVariable String eventoId) {
        return reportes.ocupacion(usuario, eventoId);
    }
}
