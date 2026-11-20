package ticketya.servicio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ticketya.PruebaIntegracion;
import ticketya.modelo.AvisoPendiente;
import ticketya.modelo.Compra;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.SolicitudDevolucion;
import ticketya.modelo.TipoAviso;
import ticketya.repositorio.ModificacionEventoRepository;
import ticketya.servicio.ModificacionEventoService.ResultadoModificacion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/** Modificación de eventos: una transacción con registro, fechas y avisos; idempotencia y versión anterior. */
class ModificacionEventoServiceTest extends PruebaIntegracion {

    private static final String EVENTO = "A";
    private static final Instant FECHA_VIEJA = Instant.parse("2026-12-01T21:00:00Z");
    // 23:30 UTC = 20:30 en Buenos Aires
    private static final Instant FECHA_NUEVA = Instant.parse("2026-12-15T23:30:00Z");

    @Autowired ModificacionEventoService servicio;
    @Autowired ModificacionEventoRepository modificaciones;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaccion;
    @MockitoSpyBean AvisoService avisoService;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void limpiarModificaciones() {
        modificaciones.deleteAll();
    }

    @AfterEach
    void restaurarSpy() {
        Mockito.reset(avisoService);
    }

    private Compra compra(String clienteId, String eventoId, EstadoCompra estado) {
        Compra c = new Compra();
        c.setClienteId(clienteId);
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
        return solicitudes.saveAndFlush(new SolicitudDevolucion(c.getClienteId(), c, c.getDetalle().get(0)));
    }

    private List<AvisoPendiente> avisosModificado() {
        return avisos.findByTipoOrderByIdAsc(TipoAviso.EVENTO_MODIFICADO);
    }

    private Instant fechaDe(Compra c) {
        return compras.findById(c.getId()).orElseThrow().getEventoFechaInicio();
    }

    private JsonNode datos(AvisoPendiente a) {
        return json.readTree(a.getDatos());
    }

    @Test
    void unaModificacionNuevaConFechaActualizaFechasYAvisaACadaCompradorConfirmado() {
        Compra c1 = compra("c1", EVENTO, EstadoCompra.CONFIRMADA);
        compra("c1", EVENTO, EstadoCompra.CONFIRMADA);
        Compra c2 = compra("c2", EVENTO, EstadoCompra.CONFIRMADA);
        Compra pendiente = compra("c3", EVENTO, EstadoCompra.PENDIENTE);
        compra("c4", EVENTO, EstadoCompra.FALLIDA);
        Compra otroEvento = compra("c5", "B", EstadoCompra.CONFIRMADA);
        SolicitudDevolucion s = solicitud(c2);

        assertThat(servicio.recibir(EVENTO, 1, FECHA_NUEVA, "Estadio Norte", "Rosario"))
                .isEqualTo(ResultadoModificacion.ACEPTADA);

        assertThat(modificaciones.existsByEventoIdAndVersion(EVENTO, 1)).isTrue();
        assertThat(fechaDe(c1)).isEqualTo(FECHA_NUEVA);
        assertThat(fechaDe(c2)).isEqualTo(FECHA_NUEVA);
        assertThat(fechaDe(pendiente)).isEqualTo(FECHA_NUEVA);
        assertThat(fechaDe(otroEvento)).isEqualTo(FECHA_VIEJA);
        assertThat(solicitudes.findById(s.getId()).orElseThrow().getEventoFechaInicio()).isEqualTo(FECHA_NUEVA);

        // Un aviso por cliente con compra confirmada: c1 (dos compras) y c2; no c3 ni c4
        assertThat(avisosModificado()).extracting(AvisoPendiente::getUsuarioId).containsExactly("c1", "c2");
        assertThat(avisosModificado()).allSatisfy(a -> {
            assertThat(a.getAcontecimientoId()).isEqualTo("evento-modificado-A-v1");
            assertThat(a.getRecursoId()).isEqualTo(EVENTO);
            assertThat(datos(a).get("evento").asString()).isEqualTo("Evento A");
            assertThat(datos(a).get("nuevaFecha").asString()).isEqualTo("15/12/2026");
            assertThat(datos(a).get("nuevoHorario").asString()).isEqualTo("20:30");
            assertThat(datos(a).get("nuevoLugar").asString()).isEqualTo("Estadio Norte, Rosario");
        });
    }

    @Test
    void siSoloCambiaElLugarNoSeTocanLasFechasYElAvisoLlevaElLugar() {
        Compra c1 = compra("c1", EVENTO, EstadoCompra.CONFIRMADA);
        SolicitudDevolucion s = solicitud(c1);

        assertThat(servicio.recibir(EVENTO, 1, null, null, "Córdoba")).isEqualTo(ResultadoModificacion.ACEPTADA);

        assertThat(fechaDe(c1)).isEqualTo(FECHA_VIEJA);
        assertThat(solicitudes.findById(s.getId()).orElseThrow().getEventoFechaInicio()).isEqualTo(FECHA_VIEJA);
        assertThat(avisosModificado()).singleElement().satisfies(a -> {
            assertThat(datos(a).get("nuevoLugar").asString()).isEqualTo("Córdoba");
            assertThat(datos(a).has("nuevaFecha")).isFalse();
            assertThat(datos(a).has("nuevoHorario")).isFalse();
        });
    }

    @Test
    void laMismaVersionRepetidaNoCambiaNadaNiCreaAvisos() {
        Compra c1 = compra("c1", EVENTO, EstadoCompra.CONFIRMADA);
        servicio.recibir(EVENTO, 1, FECHA_NUEVA, null, null);

        // Aunque el reenvío traiga otra fecha, la versión 1 ya se procesó
        assertThat(servicio.recibir(EVENTO, 1, Instant.parse("2027-01-01T00:00:00Z"), "Otro", null))
                .isEqualTo(ResultadoModificacion.YA_PROCESADA);

        assertThat(fechaDe(c1)).isEqualTo(FECHA_NUEVA);
        assertThat(avisosModificado()).hasSize(1);
        assertThat(modificaciones.count()).isEqualTo(1);
    }

    @Test
    void unaVersionAnteriorSeIgnora() {
        Compra c1 = compra("c1", EVENTO, EstadoCompra.CONFIRMADA);
        servicio.recibir(EVENTO, 3, FECHA_NUEVA, null, null);

        assertThat(servicio.recibir(EVENTO, 2, Instant.parse("2027-01-01T00:00:00Z"), null, null))
                .isEqualTo(ResultadoModificacion.VERSION_ANTERIOR);

        assertThat(fechaDe(c1)).isEqualTo(FECHA_NUEVA);
        assertThat(modificaciones.existsByEventoIdAndVersion(EVENTO, 2)).isFalse();
        assertThat(avisosModificado()).extracting(AvisoPendiente::getAcontecimientoId)
                .containsExactly("evento-modificado-A-v3");
    }

    @Test
    void unaVersionNuevaDespuesDeOtraSeProcesaYAvisaDeNuevo() {
        Compra c1 = compra("c1", EVENTO, EstadoCompra.CONFIRMADA);
        servicio.recibir(EVENTO, 1, FECHA_NUEVA, null, null);
        Instant otraFecha = Instant.parse("2026-12-20T22:00:00Z");

        assertThat(servicio.recibir(EVENTO, 2, otraFecha, null, null)).isEqualTo(ResultadoModificacion.ACEPTADA);

        assertThat(fechaDe(c1)).isEqualTo(otraFecha);
        assertThat(avisosModificado()).extracting(AvisoPendiente::getAcontecimientoId)
                .containsExactly("evento-modificado-A-v1", "evento-modificado-A-v2");
    }

    @Test
    void unEventoSinComprasRegistraLaModificacionSinAvisos() {
        assertThat(servicio.recibir(EVENTO, 1, FECHA_NUEVA, null, null)).isEqualTo(ResultadoModificacion.ACEPTADA);

        assertThat(modificaciones.existsByEventoIdAndVersion(EVENTO, 1)).isTrue();
        assertThat(avisosModificado()).isEmpty();
    }

    @Test
    void siFallaLaGeneracionDeAvisosNoQuedaNadaYElReintentoFunciona() {
        Compra c1 = compra("c1", EVENTO, EstadoCompra.CONFIRMADA);
        SolicitudDevolucion s = solicitud(c1);
        // El spy envuelve el proxy transaccional (MANDATORY): el stubbing se hace dentro de una transacción
        transaccion.executeWithoutResult(t -> doThrow(new IllegalStateException("falla al guardar los avisos"))
                .when(avisoService).eventoModificado(anyString(), anyInt(), any(), any(), any()));

        assertThatThrownBy(() -> servicio.recibir(EVENTO, 1, FECHA_NUEVA, null, null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(modificaciones.count()).isZero();
        assertThat(fechaDe(c1)).isEqualTo(FECHA_VIEJA);
        assertThat(solicitudes.findById(s.getId()).orElseThrow().getEventoFechaInicio()).isEqualTo(FECHA_VIEJA);
        assertThat(avisosModificado()).isEmpty();

        // Eventos reintenta con la misma versión
        Mockito.reset(avisoService);
        assertThat(servicio.recibir(EVENTO, 1, FECHA_NUEVA, null, null)).isEqualTo(ResultadoModificacion.ACEPTADA);
        assertThat(fechaDe(c1)).isEqualTo(FECHA_NUEVA);
        assertThat(avisosModificado()).hasSize(1);
    }
}
