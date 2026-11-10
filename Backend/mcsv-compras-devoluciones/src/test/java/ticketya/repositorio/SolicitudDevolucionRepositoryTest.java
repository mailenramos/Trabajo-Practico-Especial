package ticketya.repositorio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import ticketya.modelo.Compra;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EstadoSolicitud;
import ticketya.modelo.SolicitudDevolucion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/ticketya_compras_test?createDatabaseIfNotExist=true&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "compras.cancelacion.reanudacion-activa=false"
})
class SolicitudDevolucionRepositoryTest {

    @Autowired
    SolicitudDevolucionRepository solicitudes;

    @Autowired
    CompraRepository compras;

    @BeforeEach
    void limpiar() {
        solicitudes.deleteAll();
        compras.deleteAll();
    }

    /** Compra confirmada con una entrada; devuelve la solicitud nueva (sin guardar). */
    SolicitudDevolucion nuevaSolicitud(String entradaId) {
        Compra c = new Compra();
        c.setClienteId("cliente-1");
        c.setReservaId(UUID.randomUUID().toString());
        c.setEventoId("evento-1");
        c.setEventoNombre("Recital de prueba");
        c.setEventoFechaInicio(Instant.parse("2026-12-01T21:00:00Z"));
        c.setTotal(new BigDecimal("15000.00"));
        DetalleCompra d = new DetalleCompra();
        d.setSectorId("platea");
        d.setSectorNombre("Platea");
        d.setFila(10);
        d.setNumero(1);
        d.setPrecioUnitario(new BigDecimal("15000.00"));
        d.setEntradaId(entradaId);
        c.agregarDetalle(d);
        compras.saveAndFlush(c);
        return new SolicitudDevolucion("cliente-1", c, d);
    }

    @Test
    void unaSolicitudNuevaNacePendienteYActiva() {
        SolicitudDevolucion s = solicitudes.saveAndFlush(nuevaSolicitud("entrada-1"));

        SolicitudDevolucion guardada = solicitudes.findById(s.getId()).orElseThrow();
        assertThat(guardada.getEstado()).isEqualTo(EstadoSolicitud.PENDIENTE);
        assertThat(guardada.getEntradaActiva()).isEqualTo("entrada-1");
        assertThat(guardada.getMonto()).isEqualByComparingTo("15000.00");
        assertThat(guardada.getFila()).isEqualTo(10);
        assertThat(guardada.getNumero()).isEqualTo(1);
        assertThat(guardada.coordinacionCompleta()).isFalse();
        assertThat(solicitudes.findByEntradaActiva("entrada-1")).isPresent();
    }

    @Test
    void aprobarDesdePendienteFuncionaYUnaResueltaNoCambiaDeNuevo() {
        SolicitudDevolucion s = solicitudes.saveAndFlush(nuevaSolicitud("entrada-1"));

        assertThat(solicitudes.aprobar(s.getId(), "admin-1", Instant.now())).isEqualTo(1);
        assertThat(solicitudes.aprobar(s.getId(), "admin-1", Instant.now())).isZero();
        assertThat(solicitudes.rechazar(s.getId(), "tarde", "admin-1", Instant.now())).isZero();

        SolicitudDevolucion guardada = solicitudes.findById(s.getId()).orElseThrow();
        assertThat(guardada.getEstado()).isEqualTo(EstadoSolicitud.APROBADA);
        assertThat(guardada.getResueltaPor()).isEqualTo("admin-1");
        assertThat(guardada.getEntradaActiva()).isEqualTo("entrada-1");
    }

    @Autowired
    DetalleCompraRepository detalles;

    @Test
    void unaEntradaSeUbicaPorSuFilaDeDetalleConLaCompra() {
        SolicitudDevolucion s = nuevaSolicitud("entrada-9");

        DetalleCompra d = detalles.buscarPorEntrada("entrada-9").orElseThrow();
        assertThat(d.getCompra().getId()).isEqualTo(s.getCompraId());
        assertThat(d.getCompra().getClienteId()).isEqualTo("cliente-1");
        assertThat(detalles.buscarPorEntrada("no-existe")).isEmpty();
    }

    @Test
    void dosSolicitudesActivasDeLaMismaEntradaNoSePuedenGuardar() {
        SolicitudDevolucion primera = nuevaSolicitud("entrada-1");
        solicitudes.saveAndFlush(primera);
        Compra compra = compras.findById(primera.getCompraId()).orElseThrow();
        DetalleCompra detalle = compras.buscarConDetalle(compra.getId()).orElseThrow().getDetalle().get(0);

        assertThatThrownBy(() -> solicitudes.saveAndFlush(new SolicitudDevolucion("cliente-1", compra, detalle)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void variasSolicitudesRechazadasDeLaMismaEntradaConvivenConUnaActiva() {
        SolicitudDevolucion primera = solicitudes.saveAndFlush(nuevaSolicitud("entrada-1"));
        Compra compra = compras.findById(primera.getCompraId()).orElseThrow();
        DetalleCompra detalle = compras.buscarConDetalle(compra.getId()).orElseThrow().getDetalle().get(0);
        solicitudes.rechazar(primera.getId(), "no", "admin-1", Instant.now());
        SolicitudDevolucion segunda = solicitudes.saveAndFlush(new SolicitudDevolucion("cliente-1", compra, detalle));
        solicitudes.rechazar(segunda.getId(), "tampoco", "admin-1", Instant.now());

        SolicitudDevolucion tercera = solicitudes.saveAndFlush(new SolicitudDevolucion("cliente-1", compra, detalle));

        assertThat(solicitudes.count()).isEqualTo(3);
        assertThat(solicitudes.findByEntradaActiva("entrada-1").orElseThrow().getId()).isEqualTo(tercera.getId());
    }

    @Test
    void rechazarRegistraElMotivoYLiberaLaMarcaDeActiva() {
        SolicitudDevolucion s = solicitudes.saveAndFlush(nuevaSolicitud("entrada-1"));

        assertThat(solicitudes.rechazar(s.getId(), "Fuera de política", "admin-1", Instant.now())).isEqualTo(1);

        SolicitudDevolucion guardada = solicitudes.findById(s.getId()).orElseThrow();
        assertThat(guardada.getEstado()).isEqualTo(EstadoSolicitud.RECHAZADA);
        assertThat(guardada.getMotivoRechazo()).isEqualTo("Fuera de política");
        assertThat(guardada.getEntradaActiva()).isNull();
        assertThat(solicitudes.aprobar(s.getId(), "admin-1", Instant.now())).isZero();
    }
}
