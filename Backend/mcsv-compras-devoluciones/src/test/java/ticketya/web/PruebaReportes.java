package ticketya.web;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.ResultActions;
import ticketya.PruebaIntegracion;
import ticketya.cliente.EventosCliente.Evento;
import ticketya.cliente.EventosCliente.Sector;
import ticketya.modelo.Compra;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EntradaDeReintegro;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.EstadoReintegro;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;
import ticketya.modelo.SolicitudDevolucion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base de las pruebas de los reportes: un evento con titular, consultas con roles y datos de
 * devoluciones y reintegros. Las compras se hacen por el flujo real; las devoluciones y los
 * reintegros se registran directo en la base con el estado final que dejan los servicios.
 */
abstract class PruebaReportes extends PruebaIntegracion {

    static final String ORGANIZADOR = "organizador-1";
    static final String OTRO_ORGANIZADOR = "organizador-2";
    static final String ADMIN = "admin-1";

    @BeforeEach
    void eventoConTitular() {
        eventos.agregar(new Evento(EVENTO_ID, "Recital de prueba", Instant.parse("2026-12-01T21:00:00Z"),
                "publicado", List.of(
                new Sector(PLATEA, "Platea", new BigDecimal("15000.00")),
                new Sector(CAMPO, "Campo", new BigDecimal("8000.50"))), "RECITAL", ORGANIZADOR));
    }

    ResultActions reporte(String usuarioId, String roles, String reporte) throws Exception {
        return mvc.perform(get("/reportes/eventos/{eventoId}/" + reporte, EVENTO_ID)
                .header("X-User-Id", usuarioId).header("X-User-Roles", roles));
    }

    ResultActions comoOrganizador(String reporte) throws Exception {
        return reporte(ORGANIZADOR, "ORGANIZADOR", reporte);
    }

    ResultActions comoAdmin(String reporte) throws Exception {
        return reporte(ADMIN, "ADMIN", reporte);
    }

    // ------------------------------------------------------------------ datos

    /** Compra confirmada con la reserva estándar: 2 en Platea a 15000.00 y 2 en Campo a 8000.50. */
    UUID compraConfirmada(String clienteId) throws Exception {
        UUID id = compraPendiente(clienteId);
        pagar(clienteId, id, "clave-" + id, "CREDIT_CARD").andExpect(status().isOk());
        assertThat(compras.findById(id).orElseThrow().getEstado()).isEqualTo(EstadoCompra.CONFIRMADA);
        return id;
    }

    List<DetalleCompra> detalles(UUID compraId, String sectorId) {
        return compras.buscarConDetalle(compraId).orElseThrow().getDetalle().stream()
                .filter(d -> sectorId.equals(d.getSectorId())).toList();
    }

    /** Solicitud de devolución de una entrada del sector, que queda pendiente. */
    SolicitudDevolucion solicitudPendiente(UUID compraId, DetalleCompra detalle) {
        Compra compra = compras.findById(compraId).orElseThrow();
        return solicitudes.saveAndFlush(new SolicitudDevolucion(compra.getClienteId(), compra, detalle));
    }

    /**
     * Devolución aprobada con su reintegro en el estado indicado; con EXITOSO la entrada queda
     * devuelta (devolución completada), como la deja DevolucionService.
     */
    SolicitudDevolucion devolucion(UUID compraId, DetalleCompra detalle, EstadoReintegro estadoReintegro) {
        SolicitudDevolucion s = solicitudPendiente(compraId, detalle);
        solicitudes.aprobar(s.getId(), ADMIN, Instant.now());
        Reintegro r = new Reintegro(compraId, s.getId(), s.getMonto(), "Devolución de entrada", OrigenReintegro.DEVOLUCION);
        r.setEstado(estadoReintegro);
        reintegros.saveAndFlush(r);
        SolicitudDevolucion aprobada = solicitudes.findById(s.getId()).orElseThrow();
        aprobada.setEntradaDevuelta(estadoReintegro == EstadoReintegro.EXITOSO);
        return solicitudes.saveAndFlush(aprobada);
    }

    /**
     * Reintegro por cancelación del evento de las entradas indicadas (monto = suma de sus precios),
     * en el estado indicado; con EXITOSO sus entradas quedan devueltas, como en CancelacionService.
     */
    Reintegro cancelacion(UUID compraId, EstadoReintegro estado, List<DetalleCompra> incluidas) {
        BigDecimal monto = incluidas.stream().map(DetalleCompra::getPrecioUnitario).reduce(BigDecimal.ZERO, BigDecimal::add);
        Reintegro r = new Reintegro(compraId, UUID.randomUUID(), monto, "Cancelación del evento",
                OrigenReintegro.CANCELACION_EVENTO);
        r.setEstado(estado);
        Reintegro guardado = reintegros.saveAndFlush(r);
        for (DetalleCompra d : incluidas) {
            EntradaDeReintegro e = new EntradaDeReintegro(guardado.getId(), d.getEntradaId());
            e.setDevuelta(estado == EstadoReintegro.EXITOSO);
            entradasDeReintegro.saveAndFlush(e);
        }
        return guardado;
    }

    List<DetalleCompra> todas(UUID compraId) {
        return compras.buscarConDetalle(compraId).orElseThrow().getDetalle();
    }
}
