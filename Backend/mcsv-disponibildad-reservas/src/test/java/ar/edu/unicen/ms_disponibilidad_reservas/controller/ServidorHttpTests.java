package ar.edu.unicen.ms_disponibilidad_reservas.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP startup on the agreed port, with an isolated in-memory database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=8080", "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:http-startup-isolated;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false"
})
@DirtiesContext
class ServidorHttpTests {
    @Test
    void startsOn8080AndServesUuidErrorsWithoutUsingTheLocalDatabase() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var malformed = client.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:8080/disponibilidad/reservas/invalid")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, malformed.statusCode());
            assertTrue(malformed.body().contains("PARAMETRO_INVALIDO"));
            var missing = client.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:8080/disponibilidad/reservas/00000000-0000-0000-0000-000000000099"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());
        }
    }
}
