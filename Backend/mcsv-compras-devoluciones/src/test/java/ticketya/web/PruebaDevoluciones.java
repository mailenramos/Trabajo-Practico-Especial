package ticketya.web;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import ticketya.PruebaIntegracion;
import ticketya.cliente.EventosCliente.Evento;
import ticketya.cliente.EventosCliente.Sector;
import ticketya.modelo.DetalleCompra;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Base de las pruebas de devoluciones: compras confirmadas con entradas emitidas. */
abstract class PruebaDevoluciones extends PruebaIntegracion {

    protected static final String CLIENTE = "cliente-1";
    protected static final String ADMIN = "admin-1";

    /** El evento empieza en 30 días, así el plazo de 72 horas no depende del día de la prueba. */
    @BeforeEach
    void eventoEnTreintaDias() {
        eventoQueEmpiezaEn(Duration.ofDays(30), "publicado");
    }

    protected void eventoQueEmpiezaEn(Duration falta, String estado) {
        eventos.agregar(new Evento(EVENTO_ID, "Recital de prueba",
                Instant.now().plus(falta).truncatedTo(ChronoUnit.SECONDS), estado, List.of(
                new Sector(PLATEA, "Platea", new BigDecimal("15000.00")),
                new Sector(CAMPO, "Campo", new BigDecimal("8000.50")))));
    }

    /** Compra confirmada del cliente; devuelve sus entradas en el orden del detalle (2 de platea, 2 de campo). */
    protected List<DetalleCompra> entradasConfirmadas(String clienteId) throws Exception {
        UUID compraId = compraPendiente(clienteId);
        pagar(clienteId, compraId, "clave-" + UUID.randomUUID(), "CREDIT_CARD").andExpect(status().isOk());
        return compras.buscarConDetalle(compraId).orElseThrow().getDetalle();
    }

    protected String unaEntradaDePlatea(String clienteId) throws Exception {
        return entradasConfirmadas(clienteId).get(0).getEntradaId();
    }

    protected ResultActions solicitarDevolucion(String clienteId, String entradaId) throws Exception {
        return mvc.perform(post("/devoluciones")
                .header("X-User-Id", clienteId).header("X-User-Role", "CLIENTE")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entradaId\":\"" + entradaId + "\"}"));
    }

    /** Solicita la devolución y devuelve su id. */
    protected UUID devolucionPendiente(String clienteId, String entradaId) throws Exception {
        solicitarDevolucion(clienteId, entradaId);
        return solicitudes.findByEntradaActiva(entradaId).orElseThrow().getId();
    }

    protected ResultActions aprobar(String usuario, String rol, UUID devolucionId) throws Exception {
        return mvc.perform(post("/devoluciones/{id}/aprobacion", devolucionId)
                .header("X-User-Id", usuario).header("X-User-Role", rol));
    }

    protected ResultActions rechazar(String usuario, String rol, UUID devolucionId, String motivo) throws Exception {
        return mvc.perform(post("/devoluciones/{id}/rechazo", devolucionId)
                .header("X-User-Id", usuario).header("X-User-Role", rol)
                .contentType(MediaType.APPLICATION_JSON)
                .content(motivo == null ? "{}" : "{\"motivo\":\"" + motivo + "\"}"));
    }

    protected ResultActions reintentarReintegro(UUID reintegroId) throws Exception {
        return mvc.perform(post("/reintegros/{id}/reintento", reintegroId)
                .header("X-User-Id", ADMIN).header("X-User-Role", "ADMIN"));
    }

    protected ResultActions consultarComo(String usuario, String rol, String ruta, Object... variables) throws Exception {
        return mvc.perform(get(ruta, variables).header("X-User-Id", usuario).header("X-User-Role", rol));
    }
}
