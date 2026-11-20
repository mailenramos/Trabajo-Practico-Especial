package ticketya.repositorio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import ticketya.modelo.Compra;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.SolicitudDevolucion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Actualización masiva de la fecha de inicio del evento en compras y solicitudes de devolución. */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/ticketya_compras_test?createDatabaseIfNotExist=true&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "compras.cancelacion.reanudacion-activa=false",
        "compras.avisos.envio-activo=false"
})
class FechaDelEventoRepositoryTest {

    private static final Instant FECHA_VIEJA = Instant.parse("2026-12-01T21:00:00Z");
    private static final Instant FECHA_NUEVA = Instant.parse("2026-12-15T23:30:00Z");

    @Autowired CompraRepository compras;
    @Autowired SolicitudDevolucionRepository solicitudes;

    @BeforeEach
    void limpiar() {
        solicitudes.deleteAll();
        compras.deleteAll();
    }

    /** Compra del evento en ese estado, con una entrada emitida. */
    private Compra compra(String eventoId, EstadoCompra estado) {
        Compra c = new Compra();
        c.setClienteId("cliente-1");
        c.setReservaId(UUID.randomUUID().toString());
        c.setEventoId(eventoId);
        c.setEventoNombre("Evento " + eventoId);
        c.setEventoFechaInicio(FECHA_VIEJA);
        c.setTotal(new BigDecimal("15000.00"));
        c.setEstado(estado);
        DetalleCompra d = new DetalleCompra();
        d.setSectorId("platea");
        d.setSectorNombre("Platea");
        d.setPrecioUnitario(new BigDecimal("15000.00"));
        d.setEntradaId(UUID.randomUUID().toString());
        c.agregarDetalle(d);
        return compras.saveAndFlush(c);
    }

    private SolicitudDevolucion solicitud(Compra c) {
        return solicitudes.saveAndFlush(new SolicitudDevolucion("cliente-1", c, c.getDetalle().get(0)));
    }

    @Test
    void actualizaTodasLasComprasDelEventoEnCualquierEstadoYNoTocaOtrosEventos() {
        Compra confirmada = compra("A", EstadoCompra.CONFIRMADA);
        Compra pendiente = compra("A", EstadoCompra.PENDIENTE);
        Compra fallida = compra("A", EstadoCompra.FALLIDA);
        Compra otroEvento = compra("B", EstadoCompra.CONFIRMADA);

        assertThat(compras.actualizarFechaDelEvento("A", FECHA_NUEVA)).isEqualTo(3);

        for (Compra c : new Compra[] {confirmada, pendiente, fallida}) {
            assertThat(compras.findById(c.getId()).orElseThrow().getEventoFechaInicio()).isEqualTo(FECHA_NUEVA);
        }
        assertThat(compras.findById(otroEvento.getId()).orElseThrow().getEventoFechaInicio()).isEqualTo(FECHA_VIEJA);
    }

    @Test
    void actualizaLasSolicitudesDeDevolucionDelEventoYNoLasDeOtros() {
        SolicitudDevolucion delEvento = solicitud(compra("A", EstadoCompra.CONFIRMADA));
        SolicitudDevolucion otraDelEvento = solicitud(compra("A", EstadoCompra.CONFIRMADA));
        SolicitudDevolucion deOtroEvento = solicitud(compra("B", EstadoCompra.CONFIRMADA));

        assertThat(solicitudes.actualizarFechaDelEvento("A", FECHA_NUEVA)).isEqualTo(2);

        assertThat(solicitudes.findById(delEvento.getId()).orElseThrow().getEventoFechaInicio()).isEqualTo(FECHA_NUEVA);
        assertThat(solicitudes.findById(otraDelEvento.getId()).orElseThrow().getEventoFechaInicio()).isEqualTo(FECHA_NUEVA);
        assertThat(solicitudes.findById(deOtroEvento.getId()).orElseThrow().getEventoFechaInicio()).isEqualTo(FECHA_VIEJA);
    }

    @Test
    void unEventoSinComprasNoCambiaNada() {
        compra("B", EstadoCompra.CONFIRMADA);

        assertThat(compras.actualizarFechaDelEvento("A", FECHA_NUEVA)).isZero();
        assertThat(solicitudes.actualizarFechaDelEvento("A", FECHA_NUEVA)).isZero();
    }
}
