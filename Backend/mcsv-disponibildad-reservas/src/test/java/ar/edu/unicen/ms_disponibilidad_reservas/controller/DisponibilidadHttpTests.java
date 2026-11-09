package ar.edu.unicen.ms_disponibilidad_reservas.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:availability-http;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@DirtiesContext
class DisponibilidadHttpTests {

    private static final String BASE_PATH = "/disponibilidad/eventos/EVT-HTTP";
    private static final String INITIALIZATION = """
            {
              "limiteEntradasPorCliente": 4,
              "sectores": [
                {
                  "sectorId": "SEC-NUM",
                  "tipo": "NUMERADO",
                  "capacidadTotal": 5,
                  "asientos": [
                    { "fila": 2, "numero": 1 },
                    { "fila": 2, "numero": 2 },
                    { "fila": 10, "numero": 1 }
                  ]
                },
                {
                  "sectorId": "SEC-GEN",
                  "tipo": "NO_NUMERADO",
                  "capacidadTotal": 10,
                  "asientos": []
                }
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void limpiarDatosH2() {
        jdbcTemplate.update("DELETE FROM devolucion_detalles_cantidades");
        jdbcTemplate.update("DELETE FROM devolucion_detalles_asientos");
        jdbcTemplate.update("DELETE FROM devoluciones");
        jdbcTemplate.update("DELETE FROM detalles_reserva");
        jdbcTemplate.update("DELETE FROM reservas");
        jdbcTemplate.update("DELETE FROM cantidades_cliente_evento");
        jdbcTemplate.update("DELETE FROM asientos_disponibilidad");
        jdbcTemplate.update("DELETE FROM sectores_disponibilidad");
        jdbcTemplate.update("DELETE FROM eventos_disponibilidad");
    }

    @Test
    void postCreatesReservationAndIdenticalRetryReturnsTheExistingResource() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id='EVT-HTTP'");
        String request = """
                {"solicitudId":"SOL-HTTP","detalles":[
                  {"sectorId":"SEC-NUM","fila":2,"numero":1},
                  {"sectorId":"SEC-GEN","cantidad":2}
                ]}
                """;

        String firstBody = mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", "CLIENTE-HTTP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado", is("VIGENTE")))
                .andReturn().getResponse().getContentAsString();

        String serializedExpiry = com.jayway.jsonpath.JsonPath.read(firstBody, "$.expiraEn");
        org.junit.jupiter.api.Assertions.assertTrue(serializedExpiry.endsWith("Z"));
        Instant responseInstant = Instant.parse(serializedExpiry);

        String retryBody = mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", "CLIENTE-HTTP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado", is("VIGENTE")))
                .andReturn().getResponse().getContentAsString();
        Object firstId = com.jayway.jsonpath.JsonPath.read(firstBody, "$.reservaId");
        Object repeatedId = com.jayway.jsonpath.JsonPath.read(retryBody, "$.reservaId");
        org.junit.jupiter.api.Assertions.assertEquals(firstId.toString(), repeatedId.toString());
        LocalDateTime persistedExpiry = jdbcTemplate.queryForObject("SELECT expira_en FROM reservas WHERE id = ?",
                LocalDateTime.class, firstId.toString());
        org.junit.jupiter.api.Assertions.assertEquals(responseInstant, persistedExpiry.toInstant(ZoneOffset.UTC));

        org.junit.jupiter.api.Assertions.assertEquals(1, count("reservas"));
        org.junit.jupiter.api.Assertions.assertEquals(2, count("detalles_reserva"));
        org.junit.jupiter.api.Assertions.assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-HTTP'", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM sectores_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-GEN'",
                Integer.class));
    }

    @Test
    void reservationRequiresUserHeaderAndRejectsIdentifiersLongerThan255WithCommonBadRequest() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id='EVT-HTTP'");
        String valid = """
                {"solicitudId":"SOL-HTTP","detalles":[{"sectorId":"SEC-NUM","fila":2,"numero":1}]}
                """;

        mockMvc.perform(post(BASE_PATH + "/reservas").contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.mensaje").isNotEmpty());

        String longRequestId = "x".repeat(256);
        mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", "CLIENTE-HTTP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid.replace("SOL-HTTP", longRequestId)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", "u".repeat(256))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid))
                .andExpect(status().isBadRequest());

        org.junit.jupiter.api.Assertions.assertEquals(0, count("reservas"));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("cantidades_cliente_evento"));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM asientos_disponibilidad WHERE estado='RESERVADO'", Integer.class));
    }

    @Test
    void acceptsBothIdentifiersAtTheApproved255CharacterLimit() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id='EVT-HTTP'");
        String solicitudId = "s".repeat(255);
        String userId = "u".repeat(255);
        String body = """
                {"solicitudId":"%s","detalles":[{"sectorId":"SEC-NUM","fila":2,"numero":1}]}
                """.formatted(solicitudId);

        mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservaId").isNotEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(1, count("reservas"));
    }

    @Test
    void invalidMixedDetailShapeReturns400WithoutPersistingAReservation() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id='EVT-HTTP'");

        mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", "CLIENTE-HTTP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-BAD-SHAPE","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":1,"cantidad":1}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("reservas"));
    }

    @Test
    void nullDetailReturns400BeforeChangingReservationOrInventory() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id='EVT-HTTP'");

        mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", "CLIENTE-NULL-DETAIL")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"solicitudId\":\"SOL-NULL-DETAIL\",\"detalles\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));

        org.junit.jupiter.api.Assertions.assertEquals(0, count("reservas"));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("detalles_reserva"));
        org.junit.jupiter.api.Assertions.assertEquals("LIBRE", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-NUM' AND fila=2 AND numero=1",
                String.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM sectores_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-GEN'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-NULL-DETAIL'", Integer.class));
    }

    @Test
    void reservationRejectsUnavailableSeatAndRollsBackMixedRequest() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id='EVT-HTTP'");
        String first = """
                {"solicitudId":"SOL-TAKEN","detalles":[{"sectorId":"SEC-NUM","fila":2,"numero":1}]}
                """;
        mockMvc.perform(post(BASE_PATH + "/reservas").header("X-User-Id", "CLIENTE-A")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isCreated());

        String mixed = """
                {"solicitudId":"SOL-ROLLBACK","detalles":[
                {"sectorId":"SEC-NUM","fila":2,"numero":2},
                {"sectorId":"SEC-NUM","fila":2,"numero":1},
                {"sectorId":"SEC-GEN","cantidad":2}]}
                """;
        mockMvc.perform(post(BASE_PATH + "/reservas").header("X-User-Id", "CLIENTE-B")
                        .contentType(MediaType.APPLICATION_JSON).content(mixed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.error", is("Conflict")));

        org.junit.jupiter.api.Assertions.assertEquals(1, count("reservas"));
        org.junit.jupiter.api.Assertions.assertEquals("LIBRE", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-NUM' AND numero=2",
                String.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM sectores_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-GEN'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-B'", Integer.class));
    }

    @Test
    void putInitializesInventoryAndIdenticalRetryDoesNotDuplicateOrResetIt() throws Exception {
        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZATION))
                .andExpect(status().isNoContent());

        jdbcTemplate.update("UPDATE asientos_disponibilidad SET estado = 'RESERVADO' "
                + "WHERE evento_id = 'EVT-HTTP' AND sector_id = 'SEC-NUM' AND fila = 2 AND numero = 1");
        jdbcTemplate.update("UPDATE sectores_disponibilidad SET cantidad_reservada = 3 "
                + "WHERE evento_id = 'EVT-HTTP' AND sector_id = 'SEC-GEN'");

        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZATION))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertEquals(1, count("eventos_disponibilidad"));
        org.junit.jupiter.api.Assertions.assertEquals(2, count("sectores_disponibilidad"));
        org.junit.jupiter.api.Assertions.assertEquals(3, count("asientos_disponibilidad"));
        org.junit.jupiter.api.Assertions.assertEquals("RESERVADO", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad "
                        + "WHERE evento_id = 'EVT-HTTP' AND sector_id = 'SEC-NUM' AND fila = 2 AND numero = 1",
                String.class));
        org.junit.jupiter.api.Assertions.assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM sectores_disponibilidad "
                        + "WHERE evento_id = 'EVT-HTTP' AND sector_id = 'SEC-GEN'", Integer.class));
    }

    @Test
    void changedInitializationReturnsConflictAndPreservesTheOriginalInventory() throws Exception {
        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZATION))
                .andExpect(status().isNoContent());

        String changed = INITIALIZATION.replace("\"capacidadTotal\": 5", "\"capacidadTotal\": 4");
        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.error", is("Conflict")))
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        org.junit.jupiter.api.Assertions.assertEquals(4, jdbcTemplate.queryForObject(
                "SELECT limite_entradas_por_cliente FROM eventos_disponibilidad WHERE evento_id = 'EVT-HTTP'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(3, count("asientos_disponibilidad"));
    }

    @Test
    void invalidLimitAndMalformedJsonUseTheCommonBadRequestShape() throws Exception {
        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZATION.replace("\"limiteEntradasPorCliente\": 4", "\"limiteEntradasPorCliente\": 0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.campos", hasSize(1)))
                .andExpect(jsonPath("$.campos[0].campo", is("limiteEntradasPorCliente")))
                .andExpect(jsonPath("$.campos[0].mensaje").isNotEmpty());

        for (String invalidLimit : List.of("-1", "1", "3", "5", "null")) {
            mockMvc.perform(put(BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(INITIALIZATION.replace("\"limiteEntradasPorCliente\": 4",
                                    "\"limiteEntradasPorCliente\": " + invalidLimit)))
                    .andExpect(status().isBadRequest());
        }

        String missingLimit = INITIALIZATION.replace("  \"limiteEntradasPorCliente\": 4,\n", "");
        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingLimit))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZATION.replace("\"numero\": 2", "\"numero\": null")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ invalid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.mensaje").isNotEmpty());

        org.junit.jupiter.api.Assertions.assertEquals(0, count("eventos_disponibilidad"));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("sectores_disponibilidad"));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("asientos_disponibilidad"));
    }

    @Test
    void changingTheLimitAfterInitializationIsRejectedWithoutChangingPersistedValue() throws Exception {
        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZATION))
                .andExpect(status().isNoContent());

        String changed = INITIALIZATION.replace("\"limiteEntradasPorCliente\": 4",
                "\"limiteEntradasPorCliente\": 3");
        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changed))
                .andExpect(status().isBadRequest());

        org.junit.jupiter.api.Assertions.assertEquals(4, jdbcTemplate.queryForObject(
                "SELECT limite_entradas_por_cliente FROM eventos_disponibilidad WHERE evento_id = 'EVT-HTTP'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(2, count("sectores_disponibilidad"));
        org.junit.jupiter.api.Assertions.assertEquals(3, count("asientos_disponibilidad"));
    }

    @Test
    void unsupportedContentTypeReturnsCommon415Response() throws Exception {
        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(INITIALIZATION))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status", is(415)))
                .andExpect(jsonPath("$.error", is("Unsupported Media Type")))
                .andExpect(jsonPath("$.mensaje").isNotEmpty());
    }

    @Test
    void getReturnsFreeNumberedSeatsAndNonNumberedCalculatedAvailability() throws Exception {
        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE asientos_disponibilidad SET estado = 'RESERVADO' "
                + "WHERE evento_id = 'EVT-HTTP' AND sector_id = 'SEC-NUM' AND fila = 2 AND numero = 1");
        jdbcTemplate.update("UPDATE asientos_disponibilidad SET estado = 'VENDIDO' "
                + "WHERE evento_id = 'EVT-HTTP' AND sector_id = 'SEC-NUM' AND numero = 2");
        jdbcTemplate.update("UPDATE sectores_disponibilidad SET cantidad_reservada = 3, cantidad_vendida = 2 "
                + "WHERE evento_id = 'EVT-HTTP' AND sector_id = 'SEC-GEN'");

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventoId", is("EVT-HTTP")))
                .andExpect(jsonPath("$.sectores", hasSize(2)))
                .andExpect(jsonPath("$.sectores[?(@.sectorId == 'SEC-NUM')].tipo", contains("NUMERADO")))
                .andExpect(jsonPath("$.sectores[?(@.sectorId == 'SEC-NUM')].capacidadTotal", contains(5)))
                .andExpect(jsonPath("$.sectores[?(@.sectorId == 'SEC-NUM')].cantidadDisponible", contains(1)))
                .andExpect(jsonPath("$.sectores[?(@.sectorId == 'SEC-NUM')].asientosDisponibles[0].fila", contains(10)))
                .andExpect(jsonPath("$.sectores[?(@.sectorId == 'SEC-NUM')].asientosDisponibles[0].numero", contains(1)))
                .andExpect(jsonPath("$.sectores[?(@.sectorId == 'SEC-GEN')].cantidadDisponible", contains(5)))
                .andExpect(jsonPath("$.sectores[?(@.sectorId == 'SEC-GEN')].asientosDisponibles").doesNotExist());
    }

    @Test
    void getReturnsZeroAndAnEmptySeatListWhenNoNumberedSeatIsFree() throws Exception {
        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE asientos_disponibilidad SET estado = 'VENDIDO' "
                + "WHERE evento_id = 'EVT-HTTP' AND sector_id = 'SEC-NUM'");

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectores[?(@.sectorId == 'SEC-NUM')].cantidadDisponible", contains(0)))
                .andExpect(jsonPath("$.sectores[?(@.sectorId == 'SEC-NUM')].asientosDisponibles", hasItem(List.of())));
    }

    @Test
    void getWithoutLocalAvailabilityReturns404WithoutClaimingGlobalEventAbsence() throws Exception {
        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.error", is("Not Found")))
                .andExpect(jsonPath("$.mensaje", is(
                        "No hay disponibilidad registrada en MS2 para el evento solicitado.")))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void getReservationReturnsNumberedDetailsAndUtcExpiryWithoutChangingInventory() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id='EVT-HTTP'");
        String created = mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", "CLIENTE-CONSULTA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-CONSULTA-NUM","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":1}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservaId = com.jayway.jsonpath.JsonPath.read(created, "$.reservaId");
        Integer reservedBefore = jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-CONSULTA' AND evento_id='EVT-HTTP'",
                Integer.class);
        Integer confirmedBefore = jdbcTemplate.queryForObject(
                "SELECT cantidad_confirmada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-CONSULTA' AND evento_id='EVT-HTTP'",
                Integer.class);
        String seatStateBefore = jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-NUM' AND fila=2 AND numero=1",
                String.class);

        String response = mockMvc.perform(get("/disponibilidad/reservas/{reservaId}", reservaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservaId", is(reservaId)))
                .andExpect(jsonPath("$.clienteId", is("CLIENTE-CONSULTA")))
                .andExpect(jsonPath("$.eventoId", is("EVT-HTTP")))
                .andExpect(jsonPath("$.estado", is("VIGENTE")))
                .andExpect(jsonPath("$.detalles", hasSize(1)))
                .andExpect(jsonPath("$.detalles[0].sectorId", is("SEC-NUM")))
                .andExpect(jsonPath("$.detalles[0].fila", is(2)))
                .andExpect(jsonPath("$.detalles[0].numero", is(1)))
                .andExpect(jsonPath("$.detalles[0].cantidad").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String expiry = com.jayway.jsonpath.JsonPath.read(response, "$.expiraEn");
        org.junit.jupiter.api.Assertions.assertTrue(expiry.endsWith("Z"));
        Instant responseInstant = Instant.parse(expiry);
        LocalDateTime persistedExpiry = jdbcTemplate.queryForObject(
                "SELECT expira_en FROM reservas WHERE id = ?", LocalDateTime.class, reservaId);
        org.junit.jupiter.api.Assertions.assertEquals(responseInstant, persistedExpiry.toInstant(ZoneOffset.UTC));
        org.junit.jupiter.api.Assertions.assertEquals(reservedBefore, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-CONSULTA' AND evento_id='EVT-HTTP'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(confirmedBefore, jdbcTemplate.queryForObject(
                "SELECT cantidad_confirmada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-CONSULTA' AND evento_id='EVT-HTTP'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(seatStateBefore, jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-NUM' AND fila=2 AND numero=1",
                String.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, count("reservas"));
    }

    @Test
    void getReservationReturnsNonNumberedQuantityAndDoesNotChangeCounters() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id='EVT-HTTP'");
        String created = mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", "CLIENTE-CONSULTA-GEN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-CONSULTA-GEN","detalles":[
                                  {"sectorId":"SEC-GEN","cantidad":2}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservaId = com.jayway.jsonpath.JsonPath.read(created, "$.reservaId");
        Integer sectorReservedBefore = jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM sectores_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-GEN'",
                Integer.class);
        Integer clientReservedBefore = jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-CONSULTA-GEN' AND evento_id='EVT-HTTP'",
                Integer.class);

        mockMvc.perform(get("/disponibilidad/reservas/{reservaId}", reservaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clienteId", is("CLIENTE-CONSULTA-GEN")))
                .andExpect(jsonPath("$.eventoId", is("EVT-HTTP")))
                .andExpect(jsonPath("$.detalles", hasSize(1)))
                .andExpect(jsonPath("$.detalles[0].sectorId", is("SEC-GEN")))
                .andExpect(jsonPath("$.detalles[0].cantidad", is(2)))
                .andExpect(jsonPath("$.detalles[0].fila").doesNotExist())
                .andExpect(jsonPath("$.detalles[0].numero").doesNotExist());

        org.junit.jupiter.api.Assertions.assertEquals(sectorReservedBefore, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM sectores_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-GEN'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(clientReservedBefore, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-CONSULTA-GEN' AND evento_id='EVT-HTTP'",
                Integer.class));
    }

    @Test
    void getUnknownReservationReturnsCommon404WithoutPersistenceDetails() throws Exception {
        mockMvc.perform(get("/disponibilidad/reservas/{reservaId}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.error", is("Not Found")))
                .andExpect(jsonPath("$.mensaje", is(
                        "No existe una reserva registrada en MS2 con ese identificador.")))
                .andExpect(jsonPath("$.campos").doesNotExist())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQL"))));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("reservas"));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("cantidades_cliente_evento"));
    }

    @Test
    void confirmationReturns204AndRepeatedConfirmationDoesNotCountTwice() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id='EVT-HTTP'");
        String created = mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", "CLIENTE-CONFIRMAR-HTTP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-CONFIRMAR-HTTP","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":1},
                                  {"sectorId":"SEC-GEN","cantidad":2}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservaId = com.jayway.jsonpath.JsonPath.read(created, "$.reservaId");

        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/confirmacion", reservaId))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/confirmacion", reservaId))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertEquals("CONFIRMADA", jdbcTemplate.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, reservaId));
        org.junit.jupiter.api.Assertions.assertEquals("VENDIDO", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-NUM' AND fila=2 AND numero=1",
                String.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM sectores_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-GEN'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT cantidad_vendida FROM sectores_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-GEN'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-CONFIRMAR-HTTP' AND evento_id='EVT-HTTP'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT cantidad_confirmada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-CONFIRMAR-HTTP' AND evento_id='EVT-HTTP'",
                Integer.class));
    }

    @Test
    void paymentRejectionReleaseReturns204AndRetryDoesNotRepeatInventoryOrCountUpdates() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id='EVT-HTTP'");
        String created = mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", "CLIENTE-LIBERAR-HTTP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-LIBERAR-HTTP","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":1},
                                  {"sectorId":"SEC-GEN","cantidad":2}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservaId = com.jayway.jsonpath.JsonPath.read(created, "$.reservaId");
        String body = "{\"motivo\":\"PAGO_RECHAZADO\"}";

        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", reservaId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", reservaId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertEquals("LIBERADA", jdbcTemplate.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, reservaId));
        org.junit.jupiter.api.Assertions.assertEquals("LIBRE", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-NUM' AND fila=2 AND numero=1",
                String.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM sectores_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-GEN'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-LIBERAR-HTTP' AND evento_id='EVT-HTTP'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_confirmada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-LIBERAR-HTTP' AND evento_id='EVT-HTTP'",
                Integer.class));
    }

    @Test
    void paymentRejectionReleaseValidatesMotiveAndReturnsSafe404ForUnknownReservation() throws Exception {
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion",
                        "00000000-0000-0000-0000-000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"MOTIVO_INVALIDO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.campos[0].campo", is("motivo")));

        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion",
                        "00000000-0000-0000-0000-000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"DEVOLUCION_APROBADA\",\"devolucionId\":\"3f2504e0-4f89-41d3-9a0c-0305e82c3301\",\"detalles\":[{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":1}] }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));

        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion",
                        "00000000-0000-0000-0000-000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"PAGO_RECHAZADO\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.error", is("Not Found")))
                .andExpect(jsonPath("$.mensaje", is(
                        "No existe una reserva registrada en MS2 con ese identificador.")));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("reservas"));
    }

    @Test
    void approvedRefundIsIdempotentAndReturnsOnlyTheRequestedNumberedSeat() throws Exception {
        String reservaId = crearYConfirmarReserva("CLIENTE-REFUND-HTTP",
                "{\"solicitudId\":\"SOL-REFUND-HTTP\",\"detalles\":["
                        + "{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":1},"
                        + "{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":2}]}");
        String body = "{\"motivo\":\"DEVOLUCION_APROBADA\","
                + "\"devolucionId\":\"3f2504e0-4f89-41d3-9a0c-0305e82c3301\",\"detalles\":["
                + "{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":1}]}";

        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", reservaId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", reservaId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertEquals("CONFIRMADA", jdbcTemplate.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, reservaId));
        org.junit.jupiter.api.Assertions.assertEquals("LIBRE", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-NUM' AND fila=2 AND numero=1",
                String.class));
        org.junit.jupiter.api.Assertions.assertEquals("VENDIDO", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-NUM' AND fila=2 AND numero=2",
                String.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT cantidad_confirmada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-REFUND-HTTP' AND evento_id='EVT-HTTP'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, count("devoluciones"));
        org.junit.jupiter.api.Assertions.assertEquals(1, count("devolucion_detalles_asientos"));
    }

    @Test
    void approvedRefundReturnsNonNumberedQuantityAndRejectsExcessUnderAnotherId() throws Exception {
        String reservaId = crearYConfirmarReserva("CLIENTE-REFUND-QTY-HTTP",
                "{\"solicitudId\":\"SOL-REFUND-QTY-HTTP\",\"detalles\":["
                        + "{\"sectorId\":\"SEC-GEN\",\"cantidad\":4}]}");
        String body = "{\"motivo\":\"DEVOLUCION_APROBADA\","
                + "\"devolucionId\":\"4f2504e0-4f89-41d3-9a0c-0305e82c3301\",\"detalles\":["
                + "{\"sectorId\":\"SEC-GEN\",\"cantidad\":2}]}";

        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", reservaId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT cantidad_vendida FROM sectores_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-GEN'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT cantidad_confirmada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-REFUND-QTY-HTTP' AND evento_id='EVT-HTTP'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_reservada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-REFUND-QTY-HTTP' AND evento_id='EVT-HTTP'",
                Integer.class));

        String duplicate = body.replace("4f2504e0-4f89-41d3-9a0c-0305e82c3301",
                "5f2504e0-4f89-41d3-9a0c-0305e82c3301").replace("\"cantidad\":2", "\"cantidad\":3");
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", reservaId)
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT cantidad_vendida FROM sectores_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-GEN'",
                Integer.class));
    }

    @Test
    void approvedRefundRejectsDifferentContentForSameIdAndDuplicateRequestDetails() throws Exception {
        String reservaId = crearYConfirmarReserva("CLIENTE-REFUND-409-HTTP",
                "{\"solicitudId\":\"SOL-REFUND-409-HTTP\",\"detalles\":["
                        + "{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":1},"
                        + "{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":2}]}");
        String id = "6f2504e0-4f89-41d3-9a0c-0305e82c3301";
        String first = "{\"motivo\":\"DEVOLUCION_APROBADA\",\"devolucionId\":\"" + id
                + "\",\"detalles\":[{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":1}]}";
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", reservaId)
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isNoContent());
        String secondCreated = mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", "CLIENTE-REFUND-OTHER-RESERVATION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"solicitudId\":\"SOL-REFUND-OTHER-RESERVATION\",\"detalles\":["
                                + "{\"sectorId\":\"SEC-NUM\",\"fila\":10,\"numero\":1}] }"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String secondReservaId = com.jayway.jsonpath.JsonPath.read(secondCreated, "$.reservaId");
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/confirmacion", secondReservaId))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", secondReservaId)
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isConflict());
        String different = first.replace("\"numero\":1", "\"numero\":2");
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", reservaId)
                        .contentType(MediaType.APPLICATION_JSON).content(different))
                .andExpect(status().isConflict());
        String duplicate = "{\"motivo\":\"DEVOLUCION_APROBADA\",\"devolucionId\":\""
                + "7f2504e0-4f89-41d3-9a0c-0305e82c3301\",\"detalles\":["
                + "{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":2},"
                + "{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":2}]}";
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", reservaId)
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals("VENDIDO", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-NUM' AND fila=2 AND numero=2",
                String.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, count("devoluciones"));
    }

    @Test
    void approvedRefundRequiresConfirmedReservationAndDetailsOwnedByThatReservation() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id='EVT-HTTP'");
        String created = mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", "CLIENTE-REFUND-OWNERSHIP-HTTP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"solicitudId\":\"SOL-REFUND-OWNERSHIP-HTTP\",\"detalles\":["
                                + "{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":1}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservaId = com.jayway.jsonpath.JsonPath.read(created, "$.reservaId");
        String body = "{\"motivo\":\"DEVOLUCION_APROBADA\","
                + "\"devolucionId\":\"af2504e0-4f89-41d3-9a0c-0305e82c3301\",\"detalles\":["
                + "{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":2}]}";
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", reservaId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/confirmacion", reservaId))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", reservaId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals("VENDIDO", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-NUM' AND fila=2 AND numero=1",
                String.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("devoluciones"));
    }

    @Test
    void approvedRefundIgnoresDetailOrderAndKeepsConfirmedStateAfterFullRefund() throws Exception {
        String reservaId = crearYConfirmarReserva("CLIENTE-REFUND-ORDER-HTTP",
                "{\"solicitudId\":\"SOL-REFUND-ORDER-HTTP\",\"detalles\":["
                        + "{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":1},"
                        + "{\"sectorId\":\"SEC-GEN\",\"cantidad\":1}]}");
        String devolucionId = "8f2504e0-4f89-41d3-9a0c-0305e82c3301";
        String numbered = "{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":1}";
        String general = "{\"sectorId\":\"SEC-GEN\",\"cantidad\":1}";
        String first = "{\"motivo\":\"DEVOLUCION_APROBADA\",\"devolucionId\":\""
                + devolucionId + "\",\"detalles\":[" + numbered + "," + general + "]}";
        String reordered = "{\"motivo\":\"DEVOLUCION_APROBADA\",\"devolucionId\":\""
                + devolucionId + "\",\"detalles\":[" + general + "," + numbered + "]}";
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", reservaId)
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", reservaId)
                        .contentType(MediaType.APPLICATION_JSON).content(reordered))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertEquals("CONFIRMADA", jdbcTemplate.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, reservaId));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT cantidad_confirmada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-REFUND-ORDER-HTTP' AND evento_id='EVT-HTTP'",
                Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, count("devoluciones"));
    }

    @Test
    void approvedRefundRollsBackLedgerInventoryAndCustomerCountIfCounterUpdateFails() throws Exception {
        String reservaId = crearYConfirmarReserva("CLIENTE-REFUND-ROLLBACK-HTTP",
                "{\"solicitudId\":\"SOL-REFUND-ROLLBACK-HTTP\",\"detalles\":["
                        + "{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":1}]}");
        jdbcTemplate.execute("ALTER TABLE cantidades_cliente_evento ADD CONSTRAINT ck_http_refund_counter "
                + "CHECK (cantidad_confirmada = 1)");
        String body = "{\"motivo\":\"DEVOLUCION_APROBADA\","
                + "\"devolucionId\":\"9f2504e0-4f89-41d3-9a0c-0305e82c3301\",\"detalles\":["
                + "{\"sectorId\":\"SEC-NUM\",\"fila\":2,\"numero\":1}]}";
        try {
            mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/liberacion", reservaId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status", is(500)))
                    .andExpect(jsonPath("$.mensaje", is("Ocurrió un error interno.")));

            org.junit.jupiter.api.Assertions.assertEquals("VENDIDO", jdbcTemplate.queryForObject(
                    "SELECT estado FROM asientos_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-NUM' AND fila=2 AND numero=1",
                    String.class));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT cantidad_confirmada FROM cantidades_cliente_evento WHERE usuario_id='CLIENTE-REFUND-ROLLBACK-HTTP' AND evento_id='EVT-HTTP'",
                    Integer.class));
            org.junit.jupiter.api.Assertions.assertEquals(0, count("devoluciones"));
            org.junit.jupiter.api.Assertions.assertEquals(0, count("devolucion_detalles_asientos"));
        } finally {
            jdbcTemplate.execute("ALTER TABLE cantidades_cliente_evento DROP CONSTRAINT ck_http_refund_counter");
        }
    }

    private String crearYConfirmarReserva(String clienteId, String request) throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id='EVT-HTTP'");
        String created = mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", clienteId).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservaId = com.jayway.jsonpath.JsonPath.read(created, "$.reservaId");
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/confirmacion", reservaId))
                .andExpect(status().isNoContent());
        return reservaId;
    }

    @Test
    void confirmationUsesStable409CodesForExpiredAndReleasedReservationsAnd404ForMissing() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='PUBLICADO' WHERE evento_id='EVT-HTTP'");
        String created = mockMvc.perform(post(BASE_PATH + "/reservas")
                        .header("X-User-Id", "CLIENTE-CONFLICT-HTTP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-EXPIRADA-HTTP","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":1}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String expiredId = com.jayway.jsonpath.JsonPath.read(created, "$.reservaId");
        jdbcTemplate.update("UPDATE reservas SET expira_en=? WHERE id=?",
                LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC), expiredId);

        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/confirmacion", expiredId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("RESERVA_VENCIDA")))
                .andExpect(jsonPath("$.status", is(409)));
        org.junit.jupiter.api.Assertions.assertEquals("VIGENTE", jdbcTemplate.queryForObject(
                "SELECT estado FROM reservas WHERE id=?", String.class, expiredId));
        org.junit.jupiter.api.Assertions.assertEquals("RESERVADO", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id='EVT-HTTP' AND sector_id='SEC-NUM' AND fila=2 AND numero=1",
                String.class));

        jdbcTemplate.update("UPDATE reservas SET estado='LIBERADA' WHERE id=?", expiredId);
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/confirmacion", expiredId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("RESERVA_LIBERADA")))
                .andExpect(jsonPath("$.status", is(409)));
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/confirmacion",
                        "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("Not Found")))
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    void persistenceFailureReturnsSafe500AndRollsBackTheWholeHttpInitialization() throws Exception {
        jdbcTemplate.execute("ALTER TABLE asientos_disponibilidad "
                + "ADD CONSTRAINT ck_http_test_reject_second CHECK (numero <> 2)");
        try {
            mockMvc.perform(put(BASE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(INITIALIZATION))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status", is(500)))
                    .andExpect(jsonPath("$.error", is("Internal Server Error")))
                    .andExpect(jsonPath("$.mensaje", is("Ocurrió un error interno.")));

            org.junit.jupiter.api.Assertions.assertEquals(0, count("eventos_disponibilidad"));
            org.junit.jupiter.api.Assertions.assertEquals(0, count("sectores_disponibilidad"));
            org.junit.jupiter.api.Assertions.assertEquals(0, count("asientos_disponibilidad"));
        } finally {
            jdbcTemplate.execute("ALTER TABLE asientos_disponibilidad DROP CONSTRAINT ck_http_test_reject_second");
        }
    }

    @Test
    void activatesPendingPublicationAndRepeatedActivationLeavesReservationDataUntouched() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());

        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ACTIVA\"}"))
                .andExpect(status().isNoContent());
        assertEventSaleState("PUBLICADO", false);

        mockMvc.perform(post(BASE_PATH + "/reservas").header("X-User-Id", "CLIENTE-STATE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-STATE-ACTIVE","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":1},
                                  {"sectorId":"SEC-GEN","cantidad":2}]}
                                """))
                .andExpect(status().isCreated());
        String reservedSeat = seatState(1);
        int reservedQuantity = nonNumberedReserved();
        int customerReserved = customerQuantity("cantidad_reservada");
        int customerConfirmed = customerQuantity("cantidad_confirmada");

        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ACTIVA\"}"))
                .andExpect(status().isNoContent());
        assertEventSaleState("PUBLICADO", false);
        org.junit.jupiter.api.Assertions.assertEquals(reservedSeat, seatState(1));
        org.junit.jupiter.api.Assertions.assertEquals(reservedQuantity, nonNumberedReserved());
        org.junit.jupiter.api.Assertions.assertEquals(customerReserved, customerQuantity("cantidad_reservada"));
        org.junit.jupiter.api.Assertions.assertEquals(customerConfirmed, customerQuantity("cantidad_confirmada"));
        org.junit.jupiter.api.Assertions.assertEquals(1, count("reservas"));
    }

    @Test
    void suspendsAndResumesPublishedSalesWithoutChangingExistingHolds() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ACTIVA\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(BASE_PATH + "/reservas").header("X-User-Id", "CLIENTE-SUSPEND")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-ANTES-SUSPENDER","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":1},
                                  {"sectorId":"SEC-GEN","cantidad":2}]}
                                """))
                .andExpect(status().isCreated());
        int reservations = count("reservas");
        int reservedNonNumbered = nonNumberedReserved();
        int reservedForCustomer = customerQuantity("CLIENTE-SUSPEND", "cantidad_reservada");

        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"SUSPENDIDA\"}"))
                .andExpect(status().isNoContent());
        assertEventSaleState("PUBLICADO", true);
        org.junit.jupiter.api.Assertions.assertEquals("RESERVADO", seatState(1));
        org.junit.jupiter.api.Assertions.assertEquals(reservedNonNumbered, nonNumberedReserved());
        org.junit.jupiter.api.Assertions.assertEquals(reservedForCustomer,
                customerQuantity("CLIENTE-SUSPEND", "cantidad_reservada"));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                customerQuantity("CLIENTE-SUSPEND", "cantidad_confirmada"));

        mockMvc.perform(post(BASE_PATH + "/reservas").header("X-User-Id", "CLIENTE-DURING-SUSPENSION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-DURANTE-SUSPENSION","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":2}]}
                                """))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(reservations, count("reservas"));
        org.junit.jupiter.api.Assertions.assertEquals("LIBRE", seatState(2));

        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"SUSPENDIDA\"}"))
                .andExpect(status().isNoContent());
        assertEventSaleState("PUBLICADO", true);
        org.junit.jupiter.api.Assertions.assertEquals(reservedNonNumbered, nonNumberedReserved());
        org.junit.jupiter.api.Assertions.assertEquals(reservations, count("reservas"));

        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ACTIVA\"}"))
                .andExpect(status().isNoContent());
        assertEventSaleState("PUBLICADO", false);
        mockMvc.perform(post(BASE_PATH + "/reservas").header("X-User-Id", "CLIENTE-AFTER-RESUME")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-DESPUES-REACTIVAR","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":2}]}
                                """))
                .andExpect(status().isCreated());
        org.junit.jupiter.api.Assertions.assertEquals("RESERVADO", seatState(1));
        org.junit.jupiter.api.Assertions.assertEquals("RESERVADO", seatState(2));
        org.junit.jupiter.api.Assertions.assertEquals(reservedNonNumbered, nonNumberedReserved());
    }

    @Test
    void activeAndSuspendedSalesPreserveExhaustedLifecycleState() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE eventos_disponibilidad SET estado_evento='AGOTADO' WHERE evento_id='EVT-HTTP'");

        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ACTIVA\"}"))
                .andExpect(status().isNoContent());
        assertEventSaleState("AGOTADO", false);
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ACTIVA\"}"))
                .andExpect(status().isNoContent());
        assertEventSaleState("AGOTADO", false);

        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"SUSPENDIDA\"}"))
                .andExpect(status().isNoContent());
        assertEventSaleState("AGOTADO", true);
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"SUSPENDIDA\"}"))
                .andExpect(status().isNoContent());
        assertEventSaleState("AGOTADO", true);
        org.junit.jupiter.api.Assertions.assertEquals(0, count("reservas"));
        org.junit.jupiter.api.Assertions.assertEquals(0, nonNumberedReserved());
        org.junit.jupiter.api.Assertions.assertEquals(0, nonNumberedSold());
    }

    @Test
    void cancellationReleasesAllTemporaryReservationsAndPreservesConfirmedSales() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        activateEvent();

        String firstHold = createMixedReservation("CLIENTE-CANCEL-HOLD-1", "SOL-CANCEL-HOLD-1", 2, 1, 2);
        String secondHold = createMixedReservation("CLIENTE-CANCEL-HOLD-2", "SOL-CANCEL-HOLD-2", 2, 2, 2);
        String confirmedSale = createMixedReservation("CLIENTE-CANCEL-SOLD", "SOL-CANCEL-SOLD", 10, 1, 3);
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/confirmacion", confirmedSale))
                .andExpect(status().isNoContent());

        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"CANCELADA\"}"))
                .andExpect(status().isNoContent());

        assertEventSaleState("CANCELADO", false);
        org.junit.jupiter.api.Assertions.assertEquals("LIBERADA", reservationState(firstHold));
        org.junit.jupiter.api.Assertions.assertEquals("LIBERADA", reservationState(secondHold));
        org.junit.jupiter.api.Assertions.assertEquals("CONFIRMADA", reservationState(confirmedSale));
        org.junit.jupiter.api.Assertions.assertEquals("LIBRE", seatState(1));
        org.junit.jupiter.api.Assertions.assertEquals("LIBRE", seatState(2));
        org.junit.jupiter.api.Assertions.assertEquals("VENDIDO", seatState(10, 1));
        org.junit.jupiter.api.Assertions.assertEquals(0, nonNumberedReserved());
        org.junit.jupiter.api.Assertions.assertEquals(3, nonNumberedSold());
        assertCustomerQuantities("CLIENTE-CANCEL-HOLD-1", 0, 0);
        assertCustomerQuantities("CLIENTE-CANCEL-HOLD-2", 0, 0);
        assertCustomerQuantities("CLIENTE-CANCEL-SOLD", 0, 4);

        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"CANCELADA\"}"))
                .andExpect(status().isNoContent());
        assertEventSaleState("CANCELADO", false);
        org.junit.jupiter.api.Assertions.assertEquals(0, nonNumberedReserved());
        org.junit.jupiter.api.Assertions.assertEquals(3, nonNumberedSold());
        assertCustomerQuantities("CLIENTE-CANCEL-SOLD", 0, 4);

        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ACTIVA\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"FINALIZADA\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post(BASE_PATH + "/reservas").header("X-User-Id", "CLIENTE-POST-CANCEL")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-POST-CANCEL","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":1}]}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void cancelAndFinalizePublishedEventsWithoutReservations() throws Exception {
        String cancelPath = "/disponibilidad/eventos/EVT-HTTP";
        mockMvc.perform(put(cancelPath).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        mockMvc.perform(put(cancelPath + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ACTIVA\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(put(cancelPath + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"CANCELADA\"}"))
                .andExpect(status().isNoContent());

        String finalizePath = "/disponibilidad/eventos/EVT-HTTP-FINALIZE-EMPTY";
        mockMvc.perform(put(finalizePath).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        mockMvc.perform(put(finalizePath + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ACTIVA\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(put(finalizePath + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"FINALIZADA\"}"))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertEquals("CANCELADO", jdbcTemplate.queryForObject(
                "SELECT estado_evento FROM eventos_disponibilidad WHERE evento_id='EVT-HTTP'", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("FINALIZADO", jdbcTemplate.queryForObject(
                "SELECT estado_evento FROM eventos_disponibilidad WHERE evento_id='EVT-HTTP-FINALIZE-EMPTY'",
                String.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("reservas"));
    }

    @Test
    void finalizationReleasesHoldsAndIsIdempotent() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        activateEvent();
        String hold = createMixedReservation("CLIENTE-FINALIZE-HOLD", "SOL-FINALIZE-HOLD", 2, 1, 2);
        String confirmedSale = createMixedReservation("CLIENTE-FINALIZE-SOLD", "SOL-FINALIZE-SOLD", 2, 2, 3);
        mockMvc.perform(post("/disponibilidad/reservas/{reservaId}/confirmacion", confirmedSale))
                .andExpect(status().isNoContent());

        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"FINALIZADA\"}"))
                .andExpect(status().isNoContent());
        assertEventSaleState("FINALIZADO", false);
        org.junit.jupiter.api.Assertions.assertEquals("LIBERADA", reservationState(hold));
        org.junit.jupiter.api.Assertions.assertEquals("CONFIRMADA", reservationState(confirmedSale));
        org.junit.jupiter.api.Assertions.assertEquals("LIBRE", seatState(1));
        org.junit.jupiter.api.Assertions.assertEquals("VENDIDO", seatState(2));
        org.junit.jupiter.api.Assertions.assertEquals(0, nonNumberedReserved());
        org.junit.jupiter.api.Assertions.assertEquals(3, nonNumberedSold());
        assertCustomerQuantities("CLIENTE-FINALIZE-HOLD", 0, 0);
        assertCustomerQuantities("CLIENTE-FINALIZE-SOLD", 0, 4);

        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"FINALIZADA\"}"))
                .andExpect(status().isNoContent());
        assertEventSaleState("FINALIZADO", false);
        assertCustomerQuantities("CLIENTE-FINALIZE-HOLD", 0, 0);
        assertCustomerQuantities("CLIENTE-FINALIZE-SOLD", 0, 4);
        mockMvc.perform(post(BASE_PATH + "/reservas").header("X-User-Id", "CLIENTE-POST-FINALIZE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-POST-FINALIZE","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":1}]}
                                """))
                .andExpect(status().isConflict());
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"CANCELADA\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void terminalStateFailureRollsBackEventReservationInventoryAndCustomerQuantities() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        activateEvent();
        String hold = createMixedReservation("CLIENTE-TERMINAL-ROLLBACK", "SOL-TERMINAL-ROLLBACK", 2, 1, 2);
        jdbcTemplate.execute("ALTER TABLE cantidades_cliente_evento ADD CONSTRAINT ck_http_reject_terminal_release "
                + "CHECK (usuario_id <> 'CLIENTE-TERMINAL-ROLLBACK' OR cantidad_reservada > 0)");
        try {
            mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"estado\":\"CANCELADA\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status", is(500)))
                    .andExpect(jsonPath("$.mensaje", is("Ocurrió un error interno.")));
            assertEventSaleState("PUBLICADO", false);
            org.junit.jupiter.api.Assertions.assertEquals("VIGENTE", reservationState(hold));
            org.junit.jupiter.api.Assertions.assertEquals("RESERVADO", seatState(1));
            org.junit.jupiter.api.Assertions.assertEquals(2, nonNumberedReserved());
            org.junit.jupiter.api.Assertions.assertEquals(0, nonNumberedSold());
            assertCustomerQuantities("CLIENTE-TERMINAL-ROLLBACK", 3, 0);
        } finally {
            jdbcTemplate.execute("ALTER TABLE cantidades_cliente_evento DROP CONSTRAINT ck_http_reject_terminal_release");
        }
    }

    @Test
    void invalidMissingUninitializedAndDeferredSalesStatesReturnTheirDocumentedErrors() throws Exception {
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"NO_EXISTE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")));
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.TEXT_PLAIN).content("estado=ACTIVA"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status", is(415)));
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ACTIVA\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.mensaje", is(
                        "No hay disponibilidad registrada en MS2 para el evento solicitado.")));

        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"SUSPENDIDA\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)));
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"CANCELADA\"}"))
                .andExpect(status().isNoContent());
        assertEventSaleState("CANCELADO", false);
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"FINALIZADA\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)));
        org.junit.jupiter.api.Assertions.assertEquals(0, count("reservas"));
    }

    @Test
    void failedSalesStatePersistenceReturnsSafe500AndRollsBack() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ACTIVA\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(BASE_PATH + "/reservas").header("X-User-Id", "CLIENTE-ROLLBACK-STATE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-ROLLBACK-STATE","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":1},
                                  {"sectorId":"SEC-GEN","cantidad":2}]}
                                """))
                .andExpect(status().isCreated());
        jdbcTemplate.execute("ALTER TABLE eventos_disponibilidad ADD CONSTRAINT ck_http_reject_suspend "
                + "CHECK (venta_suspendida = FALSE)");
        try {
            mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"estado\":\"SUSPENDIDA\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status", is(500)))
                    .andExpect(jsonPath("$.mensaje", is("Ocurrió un error interno.")));
            assertEventSaleState("PUBLICADO", false);
            org.junit.jupiter.api.Assertions.assertEquals("RESERVADO", seatState(1));
            org.junit.jupiter.api.Assertions.assertEquals(2, nonNumberedReserved());
            org.junit.jupiter.api.Assertions.assertEquals(0, nonNumberedSold());
            org.junit.jupiter.api.Assertions.assertEquals(3, jdbcTemplate.queryForObject(
                    "SELECT cantidad_reservada FROM cantidades_cliente_evento "
                            + "WHERE usuario_id='CLIENTE-ROLLBACK-STATE' AND evento_id='EVT-HTTP'", Integer.class));
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT cantidad_confirmada FROM cantidades_cliente_evento "
                            + "WHERE usuario_id='CLIENTE-ROLLBACK-STATE' AND evento_id='EVT-HTTP'", Integer.class));
        } finally {
            jdbcTemplate.execute("ALTER TABLE eventos_disponibilidad DROP CONSTRAINT ck_http_reject_suspend");
        }
    }

    @Test
    void reservationIdempotencyConflictsForChangedContentAndDifferentEventWithoutChangingInventory()
            throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        activateEvent();

        mockMvc.perform(post(BASE_PATH + "/reservas").header("X-User-Id", "CLIENTE-IDEMPOTENCY-HTTP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-IDEMPOTENCY-HTTP","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":1}]}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE_PATH + "/reservas").header("X-User-Id", "CLIENTE-IDEMPOTENCY-HTTP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-IDEMPOTENCY-HTTP","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":2}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.error", is("Conflict")))
                .andExpect(jsonPath("$.mensaje").isNotEmpty());

        String otherEvent = "/disponibilidad/eventos/EVT-HTTP-OTHER";
        mockMvc.perform(put(otherEvent).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        mockMvc.perform(put(otherEvent + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ACTIVA\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(otherEvent + "/reservas").header("X-User-Id", "CLIENTE-IDEMPOTENCY-HTTP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"SOL-IDEMPOTENCY-HTTP","detalles":[
                                  {"sectorId":"SEC-NUM","fila":2,"numero":1}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.error", is("Conflict")));

        org.junit.jupiter.api.Assertions.assertEquals(1, count("reservas"));
        org.junit.jupiter.api.Assertions.assertEquals(1, count("detalles_reserva"));
        org.junit.jupiter.api.Assertions.assertEquals("RESERVADO", seatState(1));
        org.junit.jupiter.api.Assertions.assertEquals("LIBRE", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id='EVT-HTTP-OTHER' "
                        + "AND sector_id='SEC-NUM' AND fila=2 AND numero=1", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cantidades_cliente_evento WHERE evento_id='EVT-HTTP-OTHER'", Integer.class));
    }

    @Test
    void expiredReservationRetryReturnsConflictWithoutCreatingAnotherReservation() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        activateEvent();
        String body = """
                {"solicitudId":"SOL-EXPIRED-HTTP","detalles":[
                  {"sectorId":"SEC-NUM","fila":2,"numero":1}]}
                """;
        mockMvc.perform(post(BASE_PATH + "/reservas").header("X-User-Id", "CLIENTE-EXPIRED-HTTP")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE reservas SET expira_en = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) "
                + "WHERE usuario_id='CLIENTE-EXPIRED-HTTP' AND clave_idempotencia='SOL-EXPIRED-HTTP'");

        mockMvc.perform(post(BASE_PATH + "/reservas").header("X-User-Id", "CLIENTE-EXPIRED-HTTP")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.error", is("Conflict")));

        org.junit.jupiter.api.Assertions.assertEquals(1, count("reservas"));
        org.junit.jupiter.api.Assertions.assertEquals(1, count("detalles_reserva"));
        org.junit.jupiter.api.Assertions.assertEquals("RESERVADO", seatState(1));
        assertCustomerQuantities("CLIENTE-EXPIRED-HTTP", 1, 0);
    }

    @Test
    void confirmedReservationCannotBeReleasedForPaymentRejectionOverHttp() throws Exception {
        mockMvc.perform(put(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(INITIALIZATION))
                .andExpect(status().isNoContent());
        activateEvent();
        String reservaId = createMixedReservation("CLIENTE-CONFIRMED-RELEASE", "SOL-CONFIRMED-RELEASE", 2, 1, 2);

        mockMvc.perform(post("/disponibilidad/reservas/" + reservaId + "/confirmacion"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/disponibilidad/reservas/" + reservaId + "/confirmacion"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/disponibilidad/reservas/" + reservaId + "/liberacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"PAGO_RECHAZADO\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.error", is("Conflict")))
                .andExpect(jsonPath("$.mensaje").isNotEmpty());

        org.junit.jupiter.api.Assertions.assertEquals("CONFIRMADA", reservationState(reservaId));
        org.junit.jupiter.api.Assertions.assertEquals("VENDIDO", seatState(1));
        org.junit.jupiter.api.Assertions.assertEquals(0, nonNumberedReserved());
        org.junit.jupiter.api.Assertions.assertEquals(2, nonNumberedSold());
        assertCustomerQuantities("CLIENTE-CONFIRMED-RELEASE", 0, 3);
        org.junit.jupiter.api.Assertions.assertEquals(1, count("reservas"));
    }

    private void assertEventSaleState(String expectedState, boolean expectedSuspended) {
        org.junit.jupiter.api.Assertions.assertEquals(expectedState, jdbcTemplate.queryForObject(
                "SELECT estado_evento FROM eventos_disponibilidad WHERE evento_id='EVT-HTTP'", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(expectedSuspended, jdbcTemplate.queryForObject(
                "SELECT venta_suspendida FROM eventos_disponibilidad WHERE evento_id='EVT-HTTP'", Boolean.class));
    }

    private void activateEvent() throws Exception {
        mockMvc.perform(put(BASE_PATH + "/venta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ACTIVA\"}"))
                .andExpect(status().isNoContent());
    }

    private String createMixedReservation(String userId, String requestId, int fila, int seatNumber, int generalCount)
            throws Exception {
        String response = mockMvc.perform(post(BASE_PATH + "/reservas").header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"solicitudId":"%s","detalles":[
                                  {"sectorId":"SEC-NUM","fila":%d,"numero":%d},
                                  {"sectorId":"SEC-GEN","cantidad":%d}]}
                                """.formatted(requestId, fila, seatNumber, generalCount)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.reservaId");
    }

    private String reservationState(String reservaId) {
        return jdbcTemplate.queryForObject("SELECT estado FROM reservas WHERE id=?", String.class, reservaId);
    }

    private void assertCustomerQuantities(String userId, int reserved, int confirmed) {
        org.junit.jupiter.api.Assertions.assertEquals(reserved, customerQuantity(userId, "cantidad_reservada"));
        org.junit.jupiter.api.Assertions.assertEquals(confirmed, customerQuantity(userId, "cantidad_confirmada"));
    }

    private String seatState(int numero) {
        return seatState(2, numero);
    }

    private String seatState(int fila, int numero) {
        return jdbcTemplate.queryForObject("SELECT estado FROM asientos_disponibilidad "
                + "WHERE evento_id='EVT-HTTP' AND sector_id='SEC-NUM' AND fila=? AND numero=?",
                String.class, fila, numero);
    }

    private int nonNumberedReserved() {
        return jdbcTemplate.queryForObject("SELECT cantidad_reservada FROM sectores_disponibilidad "
                + "WHERE evento_id='EVT-HTTP' AND sector_id='SEC-GEN'", Integer.class);
    }

    private int nonNumberedSold() {
        return jdbcTemplate.queryForObject("SELECT cantidad_vendida FROM sectores_disponibilidad "
                + "WHERE evento_id='EVT-HTTP' AND sector_id='SEC-GEN'", Integer.class);
    }

    private int customerQuantity(String column) {
        return customerQuantity("CLIENTE-STATE", column);
    }

    private int customerQuantity(String userId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM cantidades_cliente_evento "
                + "WHERE usuario_id=? AND evento_id='EVT-HTTP'", Integer.class, userId);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
