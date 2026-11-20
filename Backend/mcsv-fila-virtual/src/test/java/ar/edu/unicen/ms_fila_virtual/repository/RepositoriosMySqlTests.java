package ar.edu.unicen.ms_fila_virtual.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import ar.edu.unicen.ms_fila_virtual.model.FilaEvento;
import ar.edu.unicen.ms_fila_virtual.model.Turno;
import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoFila;
import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoTurno;

/** Opt-in only: the supplied server must be a newly created, disposable MySQL instance. */
@EnabledIfEnvironmentVariable(named = "MS7_TEST_MYSQL_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RepositoriosMySqlTests {
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 17, 15, 0);

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        String url = System.getenv("MS7_TEST_MYSQL_URL");
        String password = System.getenv("MS7_TEST_MYSQL_PASSWORD");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ms7_disposable")
                || url.contains(":3307/") || password == null) {
            throw new IllegalStateException("Se requiere una instancia MySQL descartable dedicada; no se usan credenciales locales");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    @Autowired FilaEventoRepository filas;
    @Autowired TurnoRepository turnos;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void limpiarBaseDescartable() {
        jdbc.update("DELETE FROM turnos");
        jdbc.update("DELETE FROM filas_evento");
    }

    @Test
    void consultasDeTurnosPosicionCupoVencimientoEIdempotencia() {
        FilaEvento fila = fila("evento", EstadoFila.ACTIVA, 3);
        FilaEvento otra = fila("otro", EstadoFila.ACTIVA, 3);
        Turno primero = turno(fila, "a", EstadoTurno.ESPERANDO, null, null);
        Turno segundo = turno(fila, "b", EstadoTurno.ESPERANDO, null, null);
        turno(otra, "c", EstadoTurno.ESPERANDO, null, null);
        Turno vigente = turno(fila, "d", EstadoTurno.HABILITADO, AHORA.plusMinutes(1), null);
        Turno vencido = turno(fila, "e", EstadoTurno.HABILITADO, AHORA, null);
        Turno consumido = turno(fila, "f", EstadoTurno.CONSUMIDO, AHORA.plusMinutes(1), "sol-1");
        Turno nuevo = turno(fila, "f", EstadoTurno.ESPERANDO, null, null);

        assertEquals(primero.getId(), turnos.findActivo(fila.getId(), "a").orElseThrow().getId());
        assertEquals(nuevo.getId(), turnos.findFirstByFilaIdAndUsuarioIdOrderByIdDesc(fila.getId(), "f").orElseThrow().getId());
        assertEquals(consumido.getId(), turnos.findByUsuarioIdAndSolicitudId("f", "sol-1").orElseThrow().getId());
        assertTrue(turnos.findByUsuarioIdAndSolicitudId("F", "sol-1").isEmpty());
        assertEquals(1, turnos.countByFilaIdAndEstadoAndIdLessThan(fila.getId(), EstadoTurno.ESPERANDO, segundo.getId()));
        assertEquals(3, turnos.countByFilaIdAndEstado(fila.getId(), EstadoTurno.ESPERANDO));
        assertEquals(1, turnos.countByFilaIdAndEstadoAndExpiraEnAfter(fila.getId(), EstadoTurno.HABILITADO, AHORA));
        assertEquals(List.of(primero.getId(), segundo.getId()), turnos
                .findByFilaIdAndEstadoOrderByIdAsc(fila.getId(), EstadoTurno.ESPERANDO, PageRequest.of(0, 2))
                .stream().map(Turno::getId).toList());
        assertEquals(List.of(vencido.getId()), turnos
                .findByFilaIdAndEstadoAndExpiraEnLessThanEqualOrderByExpiraEnAscIdAsc(fila.getId(), EstadoTurno.HABILITADO, AHORA)
                .stream().map(Turno::getId).toList());
        assertEquals(List.of(primero.getId(), segundo.getId(), vigente.getId(), vencido.getId(), nuevo.getId()), turnos
                .findByFilaIdAndEstadoInOrderByIdAsc(fila.getId(), List.of(EstadoTurno.ESPERANDO, EstadoTurno.HABILITADO))
                .stream().map(Turno::getId).toList());
        tx.executeWithoutResult(s -> assertEquals(primero.getId(), turnos.findByIdForUpdate(primero.getId()).orElseThrow().getId()));
    }

    @Test
    void schedulerSeleccionaVencidasYPendientesConCupoPeroNoPausadasSinVencimiento() {
        FilaEvento libre = fila("libre", EstadoFila.ACTIVA, 1);
        FilaEvento llena = fila("llena", EstadoFila.ACTIVA, 1);
        FilaEvento pausada = fila("pausada", EstadoFila.PAUSADA, 1);
        FilaEvento pausadaVencida = fila("pausada-vencida", EstadoFila.PAUSADA, 1);
        FilaEvento cancelada = fila("cancelada", EstadoFila.ACTIVA, 1);
        cancelada.setCancelado(true);
        filas.saveAndFlush(cancelada);
        turno(libre, "a", EstadoTurno.ESPERANDO, null, null);
        turno(llena, "b", EstadoTurno.ESPERANDO, null, null);
        turno(llena, "c", EstadoTurno.HABILITADO, AHORA.plusSeconds(1), null);
        turno(pausada, "d", EstadoTurno.ESPERANDO, null, null);
        turno(pausadaVencida, "e", EstadoTurno.HABILITADO, AHORA, null);
        turno(cancelada, "f", EstadoTurno.ESPERANDO, null, null);
        assertEquals(List.of(libre.getId(), pausadaVencida.getId()), filas.findIdsParaProcesar(AHORA));
    }

    @Test
    void restriccionesDeTurnoActivoYClaveSonGlobalesSinImpedirHistorial() {
        FilaEvento a = fila("a", EstadoFila.ACTIVA, 1);
        FilaEvento b = fila("b", EstadoFila.ACTIVA, 1);
        turno(a, "usuario", EstadoTurno.CONSUMIDO, AHORA.plusMinutes(1), "solicitud");
        turno(a, "usuario", EstadoTurno.ESPERANDO, null, null);
        assertThrows(DataIntegrityViolationException.class, () -> turno(a, "usuario", EstadoTurno.ESPERANDO, null, null));
        assertThrows(DataIntegrityViolationException.class, () -> turno(b, "usuario", EstadoTurno.CONSUMIDO, AHORA.plusMinutes(1), "solicitud"));
        assertNotNull(turno(b, "usuario", EstadoTurno.ESPERANDO, null, null).getId());
    }

    @Test
    void bloqueoDeFilaSerializaTransaccionesYPermiteOtrasFilas() throws Exception {
        FilaEvento fila = fila("bloqueada", EstadoFila.ACTIVA, 1);
        FilaEvento otra = fila("independiente", EstadoFila.ACTIVA, 1);
        CountDownLatch bloqueada = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        CountDownLatch segundoIniciado = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primero = executor.submit(() -> tx.executeWithoutResult(s -> {
                filas.findByEventoIdForUpdate("bloqueada").orElseThrow();
                bloqueada.countDown();
                try { if (!liberar.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertTrue(bloqueada.await(5, TimeUnit.SECONDS));
            var segundo = executor.submit(() -> tx.executeWithoutResult(s -> {
                segundoIniciado.countDown();
                filas.findByIdForUpdate(fila.getId()).orElseThrow();
            }));
            try {
                assertTrue(segundoIniciado.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> segundo.get(300, TimeUnit.MILLISECONDS));
                tx.executeWithoutResult(s -> assertEquals(otra.getId(), filas.findByIdForUpdate(otra.getId()).orElseThrow().getId()));
            } finally { liberar.countDown(); }
            primero.get(5, TimeUnit.SECONDS);
            segundo.get(5, TimeUnit.SECONDS);
        }
    }

    private FilaEvento fila(String eventoId, EstadoFila estado, int cupo) {
        FilaEvento fila = new FilaEvento();
        fila.setEventoId(eventoId);
        fila.setEstado(estado);
        fila.setCupo(cupo);
        return filas.saveAndFlush(fila);
    }

    private Turno turno(FilaEvento fila, String usuario, EstadoTurno estado, LocalDateTime expira, String solicitud) {
        Turno turno = new Turno();
        turno.setFila(fila);
        turno.setUsuarioId(usuario);
        turno.setEstado(estado);
        turno.setIngresadoEn(AHORA.minusMinutes(1));
        if (expira != null) {
            turno.setHabilitadoEn(AHORA.minusMinutes(1));
            turno.setExpiraEn(expira);
        }
        if (solicitud != null) {
            turno.setSolicitudId(solicitud);
            turno.setHuellaSolicitud("huella");
        }
        return turnos.saveAndFlush(turno);
    }
}
