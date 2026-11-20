package ticketya.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ticketya.servicio.RecepcionService;
import ticketya.web.dto.AvisoPedido;
import ticketya.web.dto.RecepcionRespuesta;

/** Endpoint interno: lo llaman los workers de outbox de otros servicios, no los clientes. */
@RestController
public class NotificacionController {

    private final RecepcionService recepcion;

    public NotificacionController(RecepcionService recepcion) {
        this.recepcion = recepcion;
    }

    /** 202: el aviso quedó registrado; el correo se envía después. Una reentrega responde lo mismo. */
    @PostMapping("/internal/notificaciones")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RecepcionRespuesta recibir(@Valid @RequestBody AvisoPedido aviso) {
        return RecepcionRespuesta.de(recepcion.recibir(aviso));
    }
}
