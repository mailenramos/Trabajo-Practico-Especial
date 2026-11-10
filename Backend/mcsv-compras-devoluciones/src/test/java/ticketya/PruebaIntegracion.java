package ticketya;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ticketya.cliente.EventosCliente.Evento;
import ticketya.cliente.EventosCliente.Sector;
import ticketya.cliente.ReservasCliente.DetalleReserva;
import ticketya.cliente.ReservasCliente.EstadoReserva;
import ticketya.cliente.ReservasCliente.Reserva;
import ticketya.dobles.DoblesConfig;
import ticketya.dobles.EntradasFalso;
import ticketya.dobles.EventosFalso;
import ticketya.dobles.PagosFalso;
import ticketya.dobles.ReservasFalso;
import ticketya.repositorio.CancelacionEventoRepository;
import ticketya.repositorio.ClaveIdempotenciaRepository;
import ticketya.repositorio.EntradaDeReintegroRepository;
import ticketya.repositorio.CompraRepository;
import ticketya.repositorio.ReintegroRepository;
import ticketya.repositorio.SolicitudDevolucionRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base de las pruebas de punta a punta del servicio: contexto completo, base MySQL de
 * prueba y los otros servicios reemplazados por dobles de prueba.
 */
@SpringBootTest
@Import(DoblesConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/ticketya_compras_test?createDatabaseIfNotExist=true&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "compras.emision.espera-entre-intentos=0ms",
        // La reanudación automática de cancelaciones se prueba llamando al método directamente
        "compras.cancelacion.reanudacion-activa=false"
})
public abstract class PruebaIntegracion {

    protected static final String EVENTO_ID = "evento-1";
    protected static final String PLATEA = "platea";
    protected static final String CAMPO = "campo";

    @Autowired WebApplicationContext contexto;
    @Autowired protected EventosFalso eventos;
    @Autowired protected ReservasFalso reservas;
    @Autowired protected PagosFalso pagos;
    @Autowired protected EntradasFalso entradas;
    @Autowired protected CompraRepository compras;
    @Autowired protected ClaveIdempotenciaRepository claves;
    @Autowired protected SolicitudDevolucionRepository solicitudes;
    @Autowired protected ReintegroRepository reintegros;
    @Autowired protected CancelacionEventoRepository cancelaciones;
    @Autowired protected EntradaDeReintegroRepository entradasDeReintegro;

    protected MockMvc mvc;

    @BeforeEach
    void prepararBase() {
        mvc = MockMvcBuilders.webAppContextSetup(contexto).build();
        eventos.reiniciar();
        reservas.reiniciar();
        pagos.reiniciar();
        entradas.reiniciar();
        cancelaciones.deleteAll();
        entradasDeReintegro.deleteAll();
        solicitudes.deleteAll();
        reintegros.deleteAll();
        claves.deleteAll();
        compras.deleteAll();
        eventos.agregar(new Evento(EVENTO_ID, "Recital de prueba", Instant.parse("2026-12-01T21:00:00Z"),
                "publicado", List.of(
                new Sector(PLATEA, "Platea", new BigDecimal("15000.00")),
                new Sector(CAMPO, "Campo", new BigDecimal("8000.50")))));
    }

    /** Reserva vigente con dos lugares numerados en platea y dos unidades de campo. */
    protected String reservaVigente(String clienteId) {
        return reserva(clienteId, EstadoReserva.VIGENTE, List.of(
                new DetalleReserva(PLATEA, 10, 1, null),
                new DetalleReserva(PLATEA, 10, 2, null),
                new DetalleReserva(CAMPO, null, null, 2)));
    }

    protected String reserva(String clienteId, EstadoReserva estado, List<DetalleReserva> detalles) {
        String id = UUID.randomUUID().toString();
        reservas.agregar(new Reserva(id, clienteId, EVENTO_ID, estado,
                Instant.now().plus(10, ChronoUnit.MINUTES), detalles));
        return id;
    }

    protected ResultActions iniciarCompra(String clienteId, String reservaId) throws Exception {
        return mvc.perform(post("/compras")
                .header("X-User-Id", clienteId).header("X-User-Role", "CLIENTE")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reservaId\":\"" + reservaId + "\"}"));
    }

    /** Inicia la compra con los dobles y devuelve su ID. */
    protected UUID compraPendiente(String clienteId) throws Exception {
        String reservaId = reservaVigente(clienteId);
        iniciarCompra(clienteId, reservaId);
        return compras.findByReservaId(reservaId).orElseThrow().getId();
    }

    protected ResultActions pagar(String clienteId, UUID compraId, String clave, String medio) throws Exception {
        var pedido = post("/compras/{id}/pago", compraId)
                .header("X-User-Id", clienteId).header("X-User-Role", "CLIENTE")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"medioDePago\":\"" + medio + "\"}");
        if (clave != null) {
            pedido.header("Idempotency-Key", clave);
        }
        return mvc.perform(pedido);
    }

    protected ResultActions consultar(String clienteId, String ruta, Object... variables) throws Exception {
        return mvc.perform(get(ruta, variables).header("X-User-Id", clienteId).header("X-User-Role", "CLIENTE"));
    }
}
