package ticketya.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ticketya.servicio.CompraService;
import ticketya.servicio.PagoService;
import ticketya.servicio.PagoService.ResultadoPago;
import ticketya.web.dto.CompraRespuesta;
import ticketya.web.dto.IniciarCompraPedido;
import ticketya.web.dto.PagoPedido;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/compras")
public class CompraController {

    private final CompraService compraService;
    private final PagoService pagoService;

    public CompraController(CompraService compraService, PagoService pagoService) {
        this.compraService = compraService;
        this.pagoService = pagoService;
    }

    /**
     * Inicia la compra de una reserva propia y vigente. La compra nace pendiente (201).
     * Si el cliente repite el pedido y la compra sigue pendiente, la devuelve (200).
     */
    @PostMapping
    public ResponseEntity<CompraRespuesta> iniciar(UsuarioActual usuario,
                                                   @Valid @RequestBody IniciarCompraPedido pedido) {
        CompraService.InicioCompra inicio = compraService.iniciarCompra(usuario, pedido.reservaId());
        return ResponseEntity.status(inicio.nueva() ? HttpStatus.CREATED : HttpStatus.OK).body(inicio.compra());
    }

    /** Las compras del usuario, con detalle, precios del momento de la compra y estado. */
    @GetMapping
    public List<CompraRespuesta> listar(UsuarioActual usuario) {
        return compraService.listar(usuario);
    }

    /** Una compra del usuario. La compra de otro cliente da 403. */
    @GetMapping("/{compraId}")
    public CompraRespuesta obtener(UsuarioActual usuario, @PathVariable UUID compraId) {
        return compraService.obtener(usuario, compraId);
    }

    /**
     * Paga una compra pendiente. Idempotency-Key es obligatorio: repetir el pedido con la
     * misma clave devuelve el resultado original o retoma la operación sin volver a cobrar.
     */
    @PostMapping("/{compraId}/pago")
    public ResponseEntity<String> pagar(UsuarioActual usuario, @PathVariable UUID compraId,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String clave,
                                        @Valid @RequestBody PagoPedido pedido) {
        ResultadoPago resultado = pagoService.pagar(usuario, compraId, clave, pedido.medioDePago());
        return ResponseEntity.status(resultado.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(resultado.cuerpoJson());
    }
}
