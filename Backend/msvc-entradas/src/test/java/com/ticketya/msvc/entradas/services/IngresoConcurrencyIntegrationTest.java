package com.ticketya.msvc.entradas.services;

import com.ticketya.msvc.entradas.dto.EmisionRequestDTO;
import com.ticketya.msvc.entradas.dto.EntradaEmitidaDTO;
import com.ticketya.msvc.entradas.dto.EventoResumenDTO;
import com.ticketya.msvc.entradas.dto.IngresoResponseDTO;
import com.ticketya.msvc.entradas.dto.LugarAEmitirDTO;
import com.ticketya.msvc.entradas.dto.ReintegroEventoResponseDTO;
import com.ticketya.msvc.entradas.exception.BusinessConflictException;
import com.ticketya.msvc.entradas.seguridad.UsuarioActual;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validación de QR, bloqueo por devolución y cierre de evento contra PostgreSQL real
 * (US-5.2, US-5.4 y US-5.5). Requiere Docker: sin él, la clase se saltea.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IngresoConcurrencyIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("entradas")
                    .withUsername("entradas")
                    .withPassword("entradas");

    private static final UsuarioActual ADMIN = new UsuarioActual("admin-1", Set.of("ADMIN"));

    @Autowired
    private EntradaService entradaService;

    @Autowired
    private IngresoService ingresoService;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void validacionesSimultaneasDelMismoQr_soloUnaTieneExito() throws Exception {
        String evento = "evento-" + UUID.randomUUID();
        EntradaEmitidaDTO entrada = emitir(evento, 1, 1);
        int hilos = 8;

        List<Object> resultados = enParalelo(hilos,
                () -> ingresoService.validar(ADMIN, evento, entrada.codigoQr()));

        assertEquals(1, resultados.stream().filter(IngresoResponseDTO.class::isInstance).count());
        assertEquals(hilos - 1, resultados.stream()
                .filter(r -> r instanceof BusinessConflictException ex && ex.getCodigo().equals("ENTRADA_YA_UTILIZADA"))
                .count());
        assertEquals(1, jdbc.queryForObject("select count(*) from ingresos where id_entrada = ?",
                Integer.class, entrada.idEntrada()));
        assertEquals("UTILIZADA", estado(entrada.idEntrada()));
    }

    @Test
    void validacionYBloqueoPorDevolucionSimultaneos_soloUnoSeCompleta() throws Exception {
        String evento = "evento-" + UUID.randomUUID();
        for (int i = 1; i <= 10; i++) {
            EntradaEmitidaDTO entrada = emitir(evento, 1, i);

            List<Object> resultados = enParalelo(List.of(
                    () -> ingresoService.validar(ADMIN, evento, entrada.codigoQr()),
                    () -> entradaService.marcarEnReintegro(entrada.idEntrada())));

            long exitos = resultados.stream().filter(r -> !(r instanceof Exception)).count();
            assertEquals(1, exitos, "Solo una de las dos transiciones debe completarse: " + resultados);
            String final_ = estado(entrada.idEntrada());
            Integer ingresos = jdbc.queryForObject("select count(*) from ingresos where id_entrada = ?",
                    Integer.class, entrada.idEntrada());
            if (final_.equals("UTILIZADA")) {
                assertEquals(1, ingresos);
                assertTrue(resultados.stream().anyMatch(r ->
                        r instanceof BusinessConflictException ex && ex.getCodigo().equals("ENTRADA_UTILIZADA")));
            } else {
                assertEquals("EN_REINTEGRO", final_);
                assertEquals(0, ingresos);
                assertTrue(resultados.stream().anyMatch(r ->
                        r instanceof BusinessConflictException ex && ex.getCodigo().equals("ENTRADA_NO_HABILITADA")));
            }
        }
    }

    @Test
    void emisionesSimultaneasConElCierre_ningunaQuedaVigenteDespuesDelCierre() throws Exception {
        String evento = "evento-" + UUID.randomUUID();
        emitir(evento, 1, 1);
        List<Callable<Object>> tareas = new ArrayList<>();
        for (int i = 2; i <= 9; i++) {
            int numero = i;
            tareas.add(() -> emitir(evento, 1, numero));
        }
        tareas.add(() -> entradaService.reintegroPorCancelacionEvento(evento));

        List<Object> resultados = enParalelo(tareas);

        for (Object r : resultados) {
            if (r instanceof BusinessConflictException ex) {
                assertEquals("EVENTO_CERRADO", ex.getCodigo());
            } else if (r instanceof Exception ex) {
                fail("Resultado inesperado: " + ex);
            }
        }
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from entradas where id_evento = ? and estado = 'VIGENTE'", Integer.class, evento));
        BusinessConflictException tardia = assertThrows(BusinessConflictException.class, () -> emitir(evento, 2, 1));
        assertEquals("EVENTO_CERRADO", tardia.getCodigo());
    }

    @Test
    void cierreSinEntradas_esPersistenteEIdempotente() {
        String evento = "evento-" + UUID.randomUUID();

        ReintegroEventoResponseDTO primero = entradaService.reintegroPorCancelacionEvento(evento);
        ReintegroEventoResponseDTO segundo = entradaService.reintegroPorCancelacionEvento(evento);

        assertTrue(primero.cerrado());
        assertTrue(segundo.cerrado());
        assertEquals(0, segundo.entradasMarcadas());
        Instant cerradoEl = jdbc.queryForObject("select cerrado_el from control_eventos where id_evento = ?",
                java.sql.Timestamp.class, evento).toInstant();
        assertTrue(cerradoEl.isBefore(Instant.now().plusSeconds(5)));
        assertEquals("EVENTO_CERRADO",
                assertThrows(BusinessConflictException.class, () -> emitir(evento, 1, 1)).getCodigo());
    }

    @Test
    void cierreConservaLasEntradasUtilizadasYBloqueaNuevasValidaciones() {
        String evento = "evento-" + UUID.randomUUID();
        EntradaEmitidaDTO usada = emitir(evento, 1, 1);
        EntradaEmitidaDTO vigente = emitir(evento, 1, 2);
        ingresoService.validar(ADMIN, evento, usada.codigoQr());

        ReintegroEventoResponseDTO cierre = entradaService.reintegroPorCancelacionEvento(evento);

        assertEquals(1, cierre.entradasMarcadas());
        assertEquals("UTILIZADA", estado(usada.idEntrada()));
        assertEquals("EN_REINTEGRO", estado(vigente.idEntrada()));
        assertEquals("EVENTO_CERRADO", assertThrows(BusinessConflictException.class,
                () -> ingresoService.validar(ADMIN, evento, vigente.codigoQr())).getCodigo());
    }

    @Test
    void unAsientoUtilizadoSigueOcupado() {
        String evento = "evento-" + UUID.randomUUID();
        EntradaEmitidaDTO usada = emitir(evento, 4, 4);
        ingresoService.validar(ADMIN, evento, usada.codigoQr());

        assertEquals("ENTRADA_VIGENTE_DUPLICADA",
                assertThrows(BusinessConflictException.class, () -> emitir(evento, 4, 4)).getCodigo());
    }

    // ---------- Auxiliares ----------

    private EntradaEmitidaDTO emitir(String evento, int fila, int numero) {
        EmisionRequestDTO request = new EmisionRequestDTO(UUID.randomUUID(), "cliente-1",
                new EventoResumenDTO(evento, "Evento de prueba", Instant.parse("2027-01-10T21:00:00Z")),
                List.of(new LugarAEmitirDTO("sector-" + evento, "Platea", fila, numero)));
        return entradaService.emitirEntradas(request).emision().entradas().get(0);
    }

    private String estado(UUID idEntrada) {
        return jdbc.queryForObject("select estado from entradas where id = ?", String.class, idEntrada);
    }

    private static List<Object> enParalelo(int veces, Callable<Object> tarea) throws Exception {
        List<Callable<Object>> tareas = new ArrayList<>();
        for (int i = 0; i < veces; i++) {
            tareas.add(tarea);
        }
        return enParalelo(tareas);
    }

    /** Lanza todas las tareas a la vez y devuelve el resultado o la excepción de cada una. */
    private static List<Object> enParalelo(List<Callable<Object>> tareas) throws Exception {
        ExecutorService ejecutor = Executors.newFixedThreadPool(tareas.size());
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<Object>> futuros = new ArrayList<>();
            for (Callable<Object> tarea : tareas) {
                futuros.add(ejecutor.submit(() -> {
                    largada.await();
                    try {
                        return tarea.call();
                    } catch (Exception ex) {
                        return ex;
                    }
                }));
            }
            largada.countDown();
            List<Object> resultados = new ArrayList<>();
            for (Future<Object> futuro : futuros) {
                resultados.add(futuro.get(30, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            ejecutor.shutdownNow();
        }
    }
}
