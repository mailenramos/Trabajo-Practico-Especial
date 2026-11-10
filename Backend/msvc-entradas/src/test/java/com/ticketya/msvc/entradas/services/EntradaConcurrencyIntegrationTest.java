
package com.ticketya.msvc.entradas.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketya.msvc.entradas.dto.EmisionRequestDTO;
import com.ticketya.msvc.entradas.dto.EntradaResponseDTO;
import com.ticketya.msvc.entradas.dto.EventoResumenDTO;
import com.ticketya.msvc.entradas.dto.LugarAEmitirDTO;
import com.ticketya.msvc.entradas.dto.ResultadoEmisionDTO;
import com.ticketya.msvc.entradas.exception.BusinessConflictException;
import org.flywaydb.core.Flyway;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EntradaConcurrencyIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("entradas")
                    .withUsername("entradas")
                    .withPassword("entradas");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntradaService entradaService;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void iniciaPostgresYAplicaMigracionesFlywayExistentes() {
        Integer migracionesAplicadas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history "
                        + "WHERE success = TRUE AND version IN ('1', '2', '3')",
                Integer.class);

        assertEquals(3, migracionesAplicadas);
        String tipoComienzaEn = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() "
                        + "AND table_name = 'copia_evento' AND column_name = 'comienza_en'",
                String.class);
        assertEquals("timestamp with time zone", tipoComienzaEn);
    }

    @Test
    void migracionV3InterpretaLosValoresLegadosComoUtc() {
        String schema = "fecha_migracion_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target("2")
                .load()
                .migrate();

        jdbcTemplate.execute("INSERT INTO " + schema
                + ".copia_evento (id_evento, nombre, comienza_en) "
                + "VALUES ('evento-legado', 'Evento legado', TIMESTAMP '2026-12-12 21:00:00')");

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        OffsetDateTime almacenado = jdbcTemplate.queryForObject(
                "SELECT comienza_en FROM " + schema + ".copia_evento WHERE id_evento = 'evento-legado'",
                OffsetDateTime.class);
        assertEquals(Instant.parse("2026-12-12T21:00:00Z"), almacenado.toInstant());
    }

    @Test
    void emisionPersisteYDevuelveLaFechaDelEventoComoInstant() throws Exception {
        Instant comienzaEn = Instant.parse("2026-12-12T21:00:00Z");
        String idEvento = "evento-fecha-" + UUID.randomUUID();
        String idSector = "sector-fecha-" + UUID.randomUUID();
        EmisionRequestDTO request = new EmisionRequestDTO(
                UUID.randomUUID(),
                "cliente-fecha",
                new EventoResumenDTO(idEvento, "Evento con instante", comienzaEn),
                List.of(new LugarAEmitirDTO(idSector, "Platea", 1, 1)));

        ResultadoEmisionDTO resultado = entradaService.emitirEntradas(request);
        EntradaResponseDTO entrada = entradaService.consultarEntradaInterna(
                resultado.emision().entradas().get(0).idEntrada());

        assertEquals(comienzaEn, entrada.evento().comienzaEn());
        OffsetDateTime almacenado = jdbcTemplate.queryForObject(
                "SELECT comienza_en FROM copia_evento WHERE id_evento = ?",
                OffsetDateTime.class,
                idEvento);
        assertEquals(comienzaEn, almacenado.toInstant());

        String json = objectMapper.writeValueAsString(entrada);
        assertTrue(json.contains("\"comienzaEn\":\"2026-12-12T21:00:00Z\""));
        EventoResumenDTO conOffset = objectMapper.readValue(
                """
                        {"id":"evento-offset","nombre":"Evento con offset",
                         "comienzaEn":"2026-12-12T23:00:00+02:00"}
                        """,
                EventoResumenDTO.class);
        assertEquals(comienzaEn, conOffset.comienzaEn());
    }

    @Test
    void soloUnaCompraPuedeEmitirElMismoAsientoEnSimultaneo()
            throws Exception {

        int cantidadCompras = 5;

        // Identificadores propios de esta prueba para evitar interferencias.
        String idEvento = "evento-" + UUID.randomUUID();
        String idSector = "sector-" + UUID.randomUUID();

        EventoResumenDTO evento = new EventoResumenDTO(
                idEvento,
                "Evento de prueba de concurrencia",
                Instant.parse("2026-12-15T21:00:00Z")
        );

        LugarAEmitirDTO lugar = new LugarAEmitirDTO(
                idSector,
                "Sector de prueba",
                1,
                99
        );

        ExecutorService executor = Executors.newFixedThreadPool(cantidadCompras);

        CountDownLatch preparados = new CountDownLatch(cantidadCompras);
        CountDownLatch inicio = new CountDownLatch(1);

        try {
            List<Future<Object>> resultados = new ArrayList<>();

            for (int i = 0; i < cantidadCompras; i++) {

                UUID idCompra = UUID.randomUUID();

                EmisionRequestDTO request = new EmisionRequestDTO(
                        idCompra,
                        "cliente-concurrencia",
                        evento,
                        List.of(lugar)
                );

                Future<Object> futuro = executor.submit(() -> {
                    preparados.countDown();

                    // Todos esperan hasta que estén preparados.
                    inicio.await();

                    try {
                        return entradaService.emitirEntradas(request);
                    } catch (BusinessConflictException ex) {
                        return ex;
                    }
                });

                resultados.add(futuro);
            }

            // Esperamos a que los cinco hilos estén listos.
            assertTrue(
                    preparados.await(10, TimeUnit.SECONDS),
                    "Los hilos no llegaron a prepararse a tiempo"
            );

            // Liberamos los cinco intentos simultáneamente.
            inicio.countDown();

            int emisionesExitosas = 0;
            int conflictos = 0;

            for (Future<Object> futuro : resultados) {

                Object resultado = futuro.get(30, TimeUnit.SECONDS);

                if (resultado instanceof ResultadoEmisionDTO emision) {

                    assertTrue(emision.creada(),
                            "La emisión ganadora debe ser nueva");

                    emisionesExitosas++;

                } else if (resultado instanceof BusinessConflictException ex) {

                    assertEquals(
                            "ENTRADA_VIGENTE_DUPLICADA",
                            ex.getCodigo()
                    );

                    conflictos++;

                } else {
                    fail("Resultado inesperado: " + resultado);
                }
            }

            // Solo una compra debe haber conseguido el asiento.
            assertEquals(1, emisionesExitosas);
            assertEquals(cantidadCompras - 1, conflictos);

            // Consultamos directamente la base PostgreSQL.
            Integer entradasVigentes = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM entradas "
                            + "WHERE id_evento = ? "
                            + "AND id_sector = ? "
                            + "AND fila = ? "
                            + "AND numero = ? "
                            + "AND estado = 'VIGENTE'",
                    Integer.class,
                    idEvento,
                    idSector,
                    1,
                    99
            );

            assertEquals(
                    1,
                    entradasVigentes,
                    "PostgreSQL debe tener exactamente una entrada vigente para ese asiento"
            );

        } finally {
            executor.shutdownNow();
        }
    }
}
