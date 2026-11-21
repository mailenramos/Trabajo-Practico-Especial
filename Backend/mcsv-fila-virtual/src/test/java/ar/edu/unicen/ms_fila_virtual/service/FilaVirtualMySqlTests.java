package ar.edu.unicen.ms_fila_virtual.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ar.edu.unicen.ms_fila_virtual.dto.ConfiguracionFilaRequest;
import ar.edu.unicen.ms_fila_virtual.dto.ConsumirTurnoRequest;
import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoFila;
import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoTurno;
import ar.edu.unicen.ms_fila_virtual.repository.FilaEventoRepository;

/** Requires a newly created disposable MySQL server, never the development database. */
@EnabledIfEnvironmentVariable(named = "MS7_TEST_MYSQL_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(FilaVirtualMySqlTests.RelojTest.class)
class FilaVirtualMySqlTests {
    private static final Instant INICIO = Instant.parse("2026-11-17T15:00:00Z");

    @TestConfiguration
    static class RelojTest {
        @Bean @Primary RelojMutable relojMutable() { return new RelojMutable(); }
    }

    static class RelojMutable extends Clock {
        final AtomicReference<Instant> actual = new AtomicReference<>(INICIO);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return actual.get(); }
    }

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        String url = System.getenv("MS7_TEST_MYSQL_URL");
        String password = System.getenv("MS7_TEST_MYSQL_PASSWORD");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ms7_disposable")
                || url.contains(":3307/") || password == null) {
            throw new IllegalStateException("Se requiere una instancia MySQL descartable dedicada");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    @Autowired FilaVirtualService servicio;
    @Autowired FilaEventoRepository filas;
    @Autowired JdbcTemplate jdbc;
    @Autowired RelojMutable reloj;

    @BeforeEach
    void preparar() {
        jdbc.update("DELETE FROM turnos");
        jdbc.update("DELETE FROM filas_evento");
        reloj.actual.set(INICIO);
    }

    @Test
    void fifoCupoDuracionYVencimientoExacto() {
        servicio.configurar("e", new ConfiguracionFilaRequest(1, 5));
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        var a = servicio.ingresar("e", "a").turno();
        var b = servicio.ingresar("e", "b").turno();
        var c = servicio.ingresar("e", "c").turno();
        assertEquals(EstadoTurno.HABILITADO, a.estado());
        assertEquals(1L, b.posicion());
        assertEquals(2L, c.posicion());
        servicio.configurar("e", new ConfiguracionFilaRequest(2, 7));
        assertEquals(INICIO.plusSeconds(300), servicio.consultarTurno("e", "a").expiraEn());
        assertEquals(INICIO.plusSeconds(420), servicio.consultarTurno("e", "b").expiraEn());
        servicio.configurar("e", new ConfiguracionFilaRequest(1, 7));
        assertEquals(2, servicio.consultarFila("e").cantidadHabilitados());
        reloj.actual.set(INICIO.plusSeconds(300));
        assertEquals(EstadoTurno.VENCIDO, servicio.consultarTurno("e", "a").estado());
        assertEquals(EstadoTurno.ESPERANDO, servicio.consultarTurno("e", "c").estado());
        reloj.actual.set(INICIO.plusSeconds(420));
        assertEquals(EstadoTurno.HABILITADO, servicio.consultarTurno("e", "c").estado());
    }

    @Test
    void pausaRecuperaTurnoExistentePeroNoCreaYSeReanuda() {
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        var turno = servicio.ingresar("e", "a").turno();
        servicio.cambiarEstadoLocal("e", EstadoFila.PAUSADA);
        servicio.cambiarEstadoLocal("e", EstadoFila.PAUSADA);
        var repetido = servicio.ingresar("e", "a");
        assertFalse(repetido.creado());
        assertEquals(turno.turnoId(), repetido.turno().turnoId());
        assertThrows(FilaConflictException.class, () -> servicio.ingresar("e", "b"));
        assertTrue(servicio.consumir("e", peticion("a", "s")).autorizacionVigente());
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        assertTrue(servicio.ingresar("e", "b").creado());
        servicio.cambiarEstadoLocal("e", EstadoFila.DESACTIVADA);
        assertThrows(FilaConflictException.class, () -> servicio.cambiarEstadoLocal("e", EstadoFila.PAUSADA));
    }

    @Test
    void consumoHistoricoNoConsumeNuevoTurnoYRespetaVigenciaYEstado() {
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        var original = servicio.ingresar("e", "a").turno();
        var solicitud = peticion("a", "s");
        assertEquals(original.turnoId(), servicio.consumir("e", solicitud).turnoId());
        var nuevo = servicio.ingresar("e", "a").turno();
        assertEquals(original.turnoId(), servicio.consumir("e", solicitud).turnoId());
        assertEquals(nuevo.turnoId(), servicio.consultarTurno("e", "a").turnoId());
        assertThrows(FilaConflictException.class, () -> servicio.consumir("e", new ConsumirTurnoRequest("a", "s", "otra")));
        assertThrows(FilaConflictException.class, () -> servicio.consumir("evento-inexistente", solicitud));
        servicio.cambiarEstadoLocal("e", EstadoFila.DESACTIVADA);
        assertFalse(servicio.consumir("e", solicitud).autorizacionVigente());
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        reloj.actual.set(INICIO.plusSeconds(300));
        assertFalse(servicio.consumir("e", solicitud).autorizacionVigente());
        servicio.cancelar("e");
        assertFalse(servicio.consumir("e", solicitud).autorizacionVigente());
        assertThrows(FilaConflictException.class, () -> servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA));
    }

    @Test
    void cancelacionAntesDeConfiguracionEsPersistenteEIdempotente() {
        servicio.cancelar("e");
        servicio.cancelar("e");
        assertTrue(servicio.consultarFila("e").cancelado());
        assertThrows(FilaConflictException.class, () -> servicio.configurar("e", new ConfiguracionFilaRequest(1, 5)));
        assertThrows(FilaConflictException.class, () -> servicio.ingresar("e", "a"));
    }

    @Test
    void cancelacionInvalidaTurnosActivosYDesautorizaConsumoHistoricoAntesDelVencimiento() {
        servicio.configurar("e", new ConfiguracionFilaRequest(1, 5));
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        servicio.ingresar("e", "a");
        servicio.consumir("e", peticion("a", "s"));
        servicio.ingresar("e", "b");
        servicio.ingresar("e", "c");
        servicio.cancelar("e");
        assertFalse(servicio.consumir("e", peticion("a", "s")).autorizacionVigente());
        assertEquals(EstadoTurno.VENCIDO, servicio.consultarTurno("e", "b").estado());
        assertEquals(EstadoTurno.VENCIDO, servicio.consultarTurno("e", "c").estado());
        assertEquals(EstadoTurno.CONSUMIDO, servicio.consultarTurno("e", "a").estado());
    }

    @Test
    void procesamientoLocalHabilitaSiHayEsperaYCupoAunqueNoHayaVencimientos() {
        servicio.configurar("e", new ConfiguracionFilaRequest(1, 5));
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        servicio.ingresar("e", "a");
        servicio.ingresar("e", "b");
        jdbc.update("UPDATE turnos SET estado='CONSUMIDO', solicitud_id='s', huella_solicitud='h' WHERE usuario_id='a'");
        Long filaId = filas.findByEventoId("e").orElseThrow().getId();
        servicio.procesarFila(filaId);
        assertEquals(EstadoTurno.HABILITADO, servicio.consultarTurno("e", "b").estado());
    }

    @Test
    void schedulerProcesaVencidosYCupoLibreYRespetaPausasYCancelacion() {
        servicio.configurar("activa", new ConfiguracionFilaRequest(1, 5));
        servicio.cambiarEstadoLocal("activa", EstadoFila.ACTIVA);
        servicio.ingresar("activa", "a");
        servicio.ingresar("activa", "b");
        servicio.configurar("libre", new ConfiguracionFilaRequest(1, 5));
        servicio.cambiarEstadoLocal("libre", EstadoFila.ACTIVA);
        servicio.ingresar("libre", "c");
        servicio.ingresar("libre", "d");
        jdbc.update("UPDATE turnos SET estado='CONSUMIDO', solicitud_id='s', huella_solicitud='h' WHERE usuario_id='c'");
        servicio.configurar("pausada", new ConfiguracionFilaRequest(1, 5));
        servicio.cambiarEstadoLocal("pausada", EstadoFila.ACTIVA);
        servicio.ingresar("pausada", "e");
        servicio.ingresar("pausada", "f");
        servicio.cambiarEstadoLocal("pausada", EstadoFila.PAUSADA);
        servicio.cambiarEstadoLocal("cancelada", EstadoFila.ACTIVA);
        servicio.ingresar("cancelada", "g");
        servicio.cancelar("cancelada");

        reloj.actual.set(INICIO.plusSeconds(300));
        new TurnoScheduler(filas, servicio, reloj).procesarTurnos();

        assertEquals("VENCIDO", estadoPersistido("a"));
        assertEquals("HABILITADO", estadoPersistido("b"));
        assertEquals("HABILITADO", estadoPersistido("d"));
        assertEquals("VENCIDO", estadoPersistido("e"));
        assertEquals("ESPERANDO", estadoPersistido("f"));
        assertEquals("VENCIDO", estadoPersistido("g"));
    }

    @Test
    void schedulersConcurrentesRespetanFifoYCupoYUnBarridoPosteriorEsIdempotente() throws Exception {
        servicio.configurar("e", new ConfiguracionFilaRequest(1, 5));
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        servicio.ingresar("e", "a");
        servicio.ingresar("e", "b");
        servicio.ingresar("e", "c");
        reloj.actual.set(INICIO.plusSeconds(300));
        var scheduler1 = new TurnoScheduler(filas, servicio, reloj);
        var scheduler2 = new TurnoScheduler(filas, servicio, reloj);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var inicio = new CountDownLatch(1);
            var primero = pool.submit(() -> { assertTrue(inicio.await(5, TimeUnit.SECONDS)); scheduler1.procesarTurnos(); return true; });
            var segundo = pool.submit(() -> { assertTrue(inicio.await(5, TimeUnit.SECONDS)); scheduler2.procesarTurnos(); return true; });
            inicio.countDown();
            assertTrue(primero.get(15, TimeUnit.SECONDS));
            assertTrue(segundo.get(15, TimeUnit.SECONDS));
        }
        assertEquals("VENCIDO", estadoPersistido("a"));
        assertEquals("HABILITADO", estadoPersistido("b"));
        assertEquals("ESPERANDO", estadoPersistido("c"));
        var vencimiento = jdbc.queryForObject("SELECT expira_en FROM turnos WHERE usuario_id='b'", java.sql.Timestamp.class);
        scheduler1.procesarTurnos();
        assertEquals(vencimiento, jdbc.queryForObject("SELECT expira_en FROM turnos WHERE usuario_id='b'", java.sql.Timestamp.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM turnos WHERE estado='HABILITADO'", Long.class));
    }

    @Test
    void dosSchedulersSeleccionanLaMismaFilaAntesDeProcesarla() throws Exception {
        servicio.configurar("e", new ConfiguracionFilaRequest(1, 5));
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        servicio.ingresar("e", "a");
        servicio.ingresar("e", "b");
        servicio.ingresar("e", "c");
        reloj.actual.set(INICIO.plusSeconds(300));
        var seleccionadas = new java.util.concurrent.ConcurrentLinkedQueue<java.util.List<Long>>();
        var barrera = new java.util.concurrent.CyclicBarrier(2);
        FilaEventoRepository proxy = mock(FilaEventoRepository.class, delegatesTo(filas));
        doAnswer(inv -> {
            java.util.List<Long> ids = filas.findIdsParaProcesar(inv.getArgument(0));
            seleccionadas.add(ids);
            barrera.await(5, TimeUnit.SECONDS);
            return ids;
        }).when(proxy).findIdsParaProcesar(any());
        var s1 = new TurnoScheduler(proxy, servicio, reloj);
        var s2 = new TurnoScheduler(proxy, servicio, reloj);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var f1 = pool.submit(s1::procesarTurnos);
            var f2 = pool.submit(s2::procesarTurnos);
            f1.get(15, TimeUnit.SECONDS); f2.get(15, TimeUnit.SECONDS);
        }
        assertEquals(2, seleccionadas.size());
        assertEquals(1, seleccionadas.stream().distinct().count());
        assertEquals(1, seleccionadas.peek().size());
        assertEquals("VENCIDO", estadoPersistido("a"));
        assertEquals("HABILITADO", estadoPersistido("b"));
        assertEquals("ESPERANDO", estadoPersistido("c"));
    }

    @Test
    void cancelacionConcurrenteConIngresoNoDejaTurnosActivos() throws Exception {
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var inicio = new CountDownLatch(1);
            var ingreso = pool.submit(() -> {
                assertTrue(inicio.await(5, TimeUnit.SECONDS));
                try { servicio.ingresar("e", "u"); } catch (FilaConflictException esperado) { }
                return true;
            });
            var cancelacion = pool.submit(() -> { assertTrue(inicio.await(5, TimeUnit.SECONDS)); servicio.cancelar("e"); return true; });
            inicio.countDown();
            assertTrue(ingreso.get(15, TimeUnit.SECONDS));
            assertTrue(cancelacion.get(15, TimeUnit.SECONDS));
        }
        assertTrue(servicio.consultarFila("e").cancelado());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM turnos WHERE estado IN ('ESPERANDO','HABILITADO')", Long.class));
        assertThrows(FilaConflictException.class, () -> servicio.ingresar("e", "u"));
    }

    @Test
    void cancelacionConcurrenteConActivacionDejaLaFilaCerrada() throws Exception {
        servicio.configurar("e", new ConfiguracionFilaRequest(1, 5));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var inicio = new CountDownLatch(1);
            var activacion = pool.submit(() -> {
                assertTrue(inicio.await(5, TimeUnit.SECONDS));
                try { servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA); } catch (FilaConflictException esperado) { }
                return true;
            });
            var cancelacion = pool.submit(() -> { assertTrue(inicio.await(5, TimeUnit.SECONDS)); servicio.cancelar("e"); return true; });
            inicio.countDown();
            assertTrue(activacion.get(15, TimeUnit.SECONDS)); assertTrue(cancelacion.get(15, TimeUnit.SECONDS));
        }
        var fila = servicio.consultarFila("e");
        assertTrue(fila.cancelado());
        assertEquals(EstadoFila.DESACTIVADA, fila.estado());
        assertThrows(FilaConflictException.class, () -> servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA));
    }

    @Test
    void cambioDeCupoConcurrenteConSchedulerNoSuperaElLimiteYRespetaFifo() throws Exception {
        servicio.configurar("e", new ConfiguracionFilaRequest(1, 5));
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        servicio.ingresar("e", "a"); servicio.ingresar("e", "b"); servicio.ingresar("e", "c"); servicio.ingresar("e", "d");
        reloj.actual.set(INICIO.plusSeconds(300));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var inicio = new CountDownLatch(1);
            var scheduler = pool.submit(() -> { assertTrue(inicio.await(5, TimeUnit.SECONDS)); new TurnoScheduler(filas, servicio, reloj).procesarTurnos(); return true; });
            var cambio = pool.submit(() -> { assertTrue(inicio.await(5, TimeUnit.SECONDS)); servicio.configurar("e", new ConfiguracionFilaRequest(2, 5)); return true; });
            inicio.countDown();
            assertTrue(scheduler.get(15, TimeUnit.SECONDS)); assertTrue(cambio.get(15, TimeUnit.SECONDS));
        }
        assertEquals("VENCIDO", estadoPersistido("a"));
        assertEquals("HABILITADO", estadoPersistido("b"));
        assertEquals("HABILITADO", estadoPersistido("c"));
        assertEquals("ESPERANDO", estadoPersistido("d"));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM turnos WHERE estado='HABILITADO'", Long.class));
    }

    private String estadoPersistido(String usuario) {
        return jdbc.queryForObject("SELECT estado FROM turnos WHERE usuario_id=?", String.class, usuario);
    }

    @Test
    void concurrenciaDeIngresosYConsumoConMismaClave() throws Exception {
        servicio.configurar("e", new ConfiguracionFilaRequest(1, 5));
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var salida = new CountDownLatch(1);
            var ingresos = java.util.stream.IntStream.range(0, 8).mapToObj(i -> pool.submit(() -> {
                assertTrue(salida.await(5, TimeUnit.SECONDS));
                return servicio.ingresar("e", "a");
            })).toList();
            salida.countDown();
            var resultados = new java.util.ArrayList<ResultadoIngreso>();
            for (var f : ingresos) resultados.add(f.get(15, TimeUnit.SECONDS));
            assertEquals(1, resultados.stream().filter(ResultadoIngreso::creado).count());
            assertEquals(1, resultados.stream().map(r -> r.turno().turnoId()).distinct().count());
            var inicioConsumo = new CountDownLatch(1);
            var consumos = java.util.stream.IntStream.range(0, 8).mapToObj(i -> pool.submit(() -> {
                assertTrue(inicioConsumo.await(5, TimeUnit.SECONDS));
                return servicio.consumir("e", peticion("a", "s"));
            })).toList();
            inicioConsumo.countDown();
            var ids = new java.util.HashSet<Long>();
            for (var f : consumos) ids.add(f.get(15, TimeUnit.SECONDS).turnoId());
            assertEquals(1, ids.size());
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM turnos WHERE estado='CONSUMIDO'", Long.class));
        }
    }

    @Test
    void ingresosConcurrentesDeUsuariosDistintosPersistenOrdenFifoSinDuplicados() throws Exception {
        int clientes = 12;
        int cupo = 3;
        servicio.configurar("e", new ConfiguracionFilaRequest(cupo, 5));
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        try (var pool = Executors.newFixedThreadPool(clientes)) {
            var listos = new CountDownLatch(clientes);
            var inicio = new CountDownLatch(1);
            var tareas = java.util.stream.IntStream.range(0, clientes).mapToObj(i -> pool.submit(() -> {
                listos.countDown();
                assertTrue(inicio.await(5, TimeUnit.SECONDS));
                return servicio.ingresar("e", "usuario-" + i);
            })).toList();
            assertTrue(listos.await(5, TimeUnit.SECONDS));
            inicio.countDown();
            var ids = new java.util.HashSet<Long>();
            for (var tarea : tareas) {
                var resultado = tarea.get(30, TimeUnit.SECONDS);
                assertTrue(resultado.creado());
                assertTrue(ids.add(resultado.turno().turnoId()));
            }
            var filas = jdbc.queryForList("SELECT id, usuario_id, estado FROM turnos ORDER BY id");
            assertEquals(clientes, filas.size());
            assertEquals(clientes, filas.stream().map(row -> row.get("usuario_id")).distinct().count());
            for (int i = 0; i < filas.size(); i++) {
                var fila = filas.get(i);
                assertEquals(i < cupo ? "HABILITADO" : "ESPERANDO", fila.get("estado"));
                if (i >= cupo) {
                    var turno = servicio.consultarTurno("e", (String) fila.get("usuario_id"));
                    assertEquals((long) (i - cupo + 1), turno.posicion());
                }
            }
        }
    }

    @Test
    void valoresAltosDeCupoYDuracionSePersistenYAplicanSinSerPruebaDeCarga() {
        servicio.configurar("e", new ConfiguracionFilaRequest(1_000_000, 10_080));
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        var turno = servicio.ingresar("e", "usuario").turno();
        var fila = servicio.consultarFila("e");
        assertEquals(1_000_000, fila.cupo());
        assertEquals(10_080, fila.duracionMinutos());
        assertEquals(INICIO.plusSeconds(10_080L * 60), turno.expiraEn());
    }

    @Test
    void configuracionesInicialesConcurrentesCreanUnaSolaFila() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var tareas = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                servicio.configurar("e", new ConfiguracionFilaRequest(2, 5));
                return true;
            })).toList();
            start.countDown();
            for (var tarea : tareas) assertTrue(tarea.get(15, TimeUnit.SECONDS));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM filas_evento WHERE evento_id='e'", Long.class));
        }
    }

    @Test
    void claveConcurrenteEntreEventosSoloPuedeConsumirUnTurno() throws Exception {
        servicio.cambiarEstadoLocal("e1", EstadoFila.ACTIVA);
        servicio.cambiarEstadoLocal("e2", EstadoFila.ACTIVA);
        servicio.ingresar("e1", "a");
        servicio.ingresar("e2", "a");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var f1 = pool.submit(() -> intentarConsumo(start, "e1"));
            var f2 = pool.submit(() -> intentarConsumo(start, "e2"));
            start.countDown();
            assertNotEquals(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM turnos WHERE estado='CONSUMIDO'", Long.class));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM turnos WHERE estado='HABILITADO'", Long.class));
        }
    }

    @Test
    void clavesDiferentesConcurrentesNoPuedenConsumirElMismoTurno() throws Exception {
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        servicio.ingresar("e", "a");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var f1 = pool.submit(() -> intentarConsumoConClave(start, "s1"));
            var f2 = pool.submit(() -> intentarConsumoConClave(start, "s2"));
            start.countDown();
            assertNotEquals(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM turnos WHERE estado='CONSUMIDO'", Long.class));
        }
    }

    private boolean intentarConsumoConClave(CountDownLatch start, String solicitud) throws InterruptedException {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try { servicio.consumir("e", peticion("a", solicitud)); return true; }
        catch (FilaConflictException esperado) { return false; }
    }

    private boolean intentarConsumo(CountDownLatch start, String evento) throws InterruptedException {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try { servicio.consumir(evento, peticion("a", "s")); return true; }
        catch (FilaConflictException esperado) { return false; }
    }

    private ConsumirTurnoRequest peticion(String usuario, String solicitud) {
        return new ConsumirTurnoRequest(usuario, solicitud, "v1:huella");
    }
}
