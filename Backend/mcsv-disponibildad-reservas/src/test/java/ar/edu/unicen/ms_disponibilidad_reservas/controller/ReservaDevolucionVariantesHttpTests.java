package ar.edu.unicen.ms_disponibilidad_reservas.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

/** Regression checks over real HTTP, exclusively against this in-memory database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:refund-variant-http;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false"
})
@DirtiesContext
class ReservaDevolucionVariantesHttpTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired JsonMapper mapper;
    private String event;
    private String client;
    private static final String SEAT = "{\"sectorId\":\"SEC-01\",\"fila\":1,\"numero\":2}";
    private static final String QUANTITY = "{\"sectorId\":\"SEC-02\",\"cantidad\":2}";

    @BeforeEach
    void initializeIsolatedInventory() throws Exception {
        try (var connection = db.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));
        }
        event = "EVT-" + UUID.randomUUID();
        client = "CLIENTE-" + UUID.randomUUID();
        request("PUT", "/disponibilidad/eventos/" + event, """
                {"limiteEntradasPorCliente":10,"sectores":[
                  {"sectorId":"SEC-01","tipo":"NUMERADO","capacidadTotal":3,
                   "asientos":[{"fila":1,"numero":2},{"fila":1,"numero":3}]},
                  {"sectorId":"SEC-02","tipo":"NO_NUMERADO","capacidadTotal":20,"asientos":[]}]}
                """, 204);
        request("PUT", "/disponibilidad/eventos/" + event + "/venta", "{\"estado\":\"ACTIVA\"}", 204);
    }

    private String request(String method, String path, String body, int expected) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json").header("X-User-Id", client)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(expected, response.statusCode(), response.body());
            if (expected == 400 || expected == 409) {
                assertEquals(expected, mapper.readTree(response.body()).get("status").asInt());
                assertTrue(mapper.readTree(response.body()).has("mensaje"));
            }
            return response.body();
        }
    }

    private String reserve(String key, String details, int status) throws Exception {
        return request("POST", "/disponibilidad/eventos/" + event + "/reservas",
                "{\"solicitudId\":\"" + key + "\",\"detalles\":[" + details + "]}", status);
    }

    private String confirmed(String details) throws Exception {
        String id = mapper.readTree(reserve(UUID.randomUUID().toString(), details, 201)).get("reservaId").asString();
        request("POST", "/disponibilidad/reservas/" + id + "/confirmacion", null, 204);
        return id;
    }

    private void refund(String id, String key, String details, int status) throws Exception {
        request("POST", "/disponibilidad/reservas/" + id + "/liberacion",
                "{\"motivo\":\"DEVOLUCION_APROBADA\",\"devolucionId\":\"" + key
                        + "\",\"detalles\":[" + details + "]}", status);
    }

    // Includes reservation states/fingerprints, inventory, client counters and the complete refund ledger.
    private List<?> snapshot() {
        return List.of("eventos_disponibilidad", "sectores_disponibilidad", "asientos_disponibilidad",
                "reservas", "detalles_reserva", "cantidades_cliente_evento", "devoluciones",
                "devolucion_detalles_asientos", "devolucion_detalles_cantidades")
                .stream().map(table -> db.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refundRetriesCompareReservationUuidValuesInBothDirections(boolean uppercaseFirst) throws Exception {
        String id = confirmed(SEAT + "," + QUANTITY);
        String key = UUID.randomUUID().toString();
        refund(uppercaseFirst ? id.toUpperCase(Locale.ROOT) : id, key, QUANTITY, 204);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM devoluciones WHERE id=?", Integer.class, key));
        assertEquals(0, db.queryForObject("SELECT cantidad_vendida FROM sectores_disponibilidad WHERE evento_id=? AND sector_id='SEC-02'", Integer.class, event));
        assertEquals(1, db.queryForObject("SELECT cantidad_confirmada FROM cantidades_cliente_evento WHERE evento_id=?", Integer.class, event));
        var before = snapshot();
        refund(uppercaseFirst ? id : id.toUpperCase(Locale.ROOT), key, QUANTITY, 204);
        assertEquals(before, snapshot());
    }

    @Test
    void refundKeyCannotBeReusedForAnotherReservation() throws Exception {
        String first = confirmed(SEAT + "," + QUANTITY);
        String second = confirmed(QUANTITY);
        String key = UUID.randomUUID().toString();
        refund(first, key, QUANTITY, 204);
        var before = snapshot();
        refund(second, key, QUANTITY, 409);
        assertEquals(before, snapshot());
    }

    @Test
    void refundKeyCannotBeReusedForDifferentDetails() throws Exception {
        String id = confirmed(SEAT + "," + QUANTITY);
        String key = UUID.randomUUID().toString();
        refund(id, key, QUANTITY, 204);
        var before = snapshot();
        refund(id, key, SEAT, 409);
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"sectorId\":\"SEC-01\",\"fila\":1,\"numero\":2,\"cantidad\":null}",
        "{\"sectorId\":\"SEC-02\",\"fila\":null,\"numero\":null,\"cantidad\":2}",
        "{\"sectorId\":\"SEC-01\",\"fila\":1}",
        "{\"sectorId\":\"SEC-01\",\"numero\":2}",
        "{\"sectorId\":\"SEC-02\",\"cantidad\":null}",
        "{\"sectorId\":\"SEC-01\",\"fila\":1,\"numero\":2,\"cantidad\":2}",
        "{\"sectorId\":\"SEC-02\",\"fila\":1,\"cantidad\":2}",
        "{\"sectorId\":\"SEC-01\",\"fila\":null,\"numero\":2}",
        "{\"sectorId\":null,\"cantidad\":2}",
        "{\"sectorId\":\"SEC-02\",\"cantidad\":2,\"desconocido\":null}"
    })
    void invalidDetailIsRejectedBeforeAnyReservationOrRefundWrite(String detail) throws Exception {
        var before = snapshot();
        reserve("INVALID", detail, 400);
        assertEquals(before, snapshot());
        String id = confirmed(SEAT + "," + QUANTITY);
        String key = UUID.randomUUID().toString();
        before = snapshot();
        refund(id, key, detail, 400);
        assertEquals(before, snapshot());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM devoluciones WHERE id=?", Integer.class, key));
    }

    @ParameterizedTest
    @ValueSource(strings = {SEAT, QUANTITY})
    void validVariantsPreserveReservationAndRefundIdempotency(String detail) throws Exception {
        String key = UUID.randomUUID().toString();
        String first = reserve(key, detail, 201);
        var before = snapshot();
        String retry = reserve(key, detail, 200);
        assertEquals(mapper.readTree(first), mapper.readTree(retry));
        assertEquals(before, snapshot());
        String id = mapper.readTree(first).get("reservaId").asString();
        request("POST", "/disponibilidad/reservas/" + id + "/confirmacion", null, 204);
        String refundKey = UUID.randomUUID().toString();
        refund(id, refundKey, detail, 204);
        before = snapshot();
        refund(id, refundKey, detail, 204);
        assertEquals(before, snapshot());
    }
}
