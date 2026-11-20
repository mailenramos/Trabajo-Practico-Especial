package com.ticketya.gestionlugares.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.ticketya.gestionlugares.repository.LugarRepository;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.config.DevSampleDataInitializer;
import com.ticketya.gestionlugares.outbox.EventoCancelacionOutboxBackfill;
import com.ticketya.gestionlugares.outbox.OutboxScheduler;
import com.ticketya.gestionlugares.service.EventoFinalizationScheduler;

@SpringBootTest(properties = {"spring.data.mongodb.auto-index-creation=false",
        "spring.data.mongodb.uri=mongodb://127.0.0.1:1/openapi-test?directConnection=true"})
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @MockBean
    private LugarRepository lugarRepository;
    @MockBean
    private DevSampleDataInitializer devSampleDataInitializer;
    @MockBean
    private EventoRepository eventoRepository;
    @MockBean
    private EventoCancelacionOutboxBackfill backfill;
    @MockBean
    private OutboxScheduler outboxScheduler;
    @MockBean
    private EventoFinalizationScheduler finalizationScheduler;

    @Test
    void publicaOperacionesParametrosRespuestasYEjemplosOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("TicketYa - Gestión de lugares, sectores y eventos"))
                .andExpect(jsonPath("$.paths['/lugares'].post").exists())
                .andExpect(jsonPath("$.paths['/lugares'].get").exists())
                .andExpect(jsonPath("$.paths['/lugares/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/lugares/{id}/sectores'].get").exists())
                .andExpect(jsonPath("$.paths['/lugares/{id}/sectores'].post").exists())
                .andExpect(jsonPath("$.paths['/eventos'].post").exists())
                .andExpect(jsonPath("$.paths['/eventos'].get").exists())
                .andExpect(jsonPath("$.paths['/eventos/{eventoId}'].get").exists())
                .andExpect(jsonPath("$.paths['/eventos/{eventoId}/sectores/{sectorId}/precio'].put").exists())
                .andExpect(jsonPath("$.paths['/eventos/{eventoId}/limite-entradas-por-cliente'].put").exists())
                .andExpect(jsonPath("$.paths['/internal/eventos/{eventoId}'].get").exists())
                .andExpect(jsonPath("$.paths['/lugares'].get.parameters[?(@.name == 'X-User-Roles')].schema.enum").exists())
                .andExpect(jsonPath("$.paths['/lugares'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/lugares'].get.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/lugares'].post.requestBody.content['application/json'].examples.Lugar").exists())
                .andExpect(jsonPath("$.paths['/lugares/{id}/sectores'].post.requestBody.content['application/json'].examples['Sector numerado']").exists())
                .andExpect(jsonPath("$.paths['/lugares/{id}/sectores'].post.requestBody.content['application/json'].examples['Sector no numerado']").exists())
                .andExpect(jsonPath("$.paths['/lugares/{id}/sectores'].post.responses['404']").exists());
    }

    @Test
    void elPuertoPredeterminadoEs8081() {
        org.junit.jupiter.api.Assertions.assertEquals("8081", environment.getProperty("server.port"));
    }

    @Test
    void documentaCicloDeEventosCallbacksErroresYEjemplos() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode api = new ObjectMapper().readTree(body);

        assertTrue(api.path("info").path("description").asText().contains("publicación con inicialización en Reservas"));
        JsonNode eventsTag = api.path("tags").findValues("name").isEmpty()
                ? api.path("tags").get(0)
                : java.util.stream.StreamSupport.stream(api.path("tags").spliterator(), false)
                        .filter(tag -> tag.path("name").asText().equals("Eventos")).findFirst().orElseThrow();
        assertTrue(eventsTag.path("description").asText().contains("callback interno de disponibilidad"));

        JsonNode paths = api.path("paths");
        JsonNode searchOperation = paths.path("/eventos").path("get");
        JsonNode searchParameters = searchOperation.path("parameters");
        for (String parameter : List.of("tipo", "organizadorId", "participante", "ciudad", "fechaDesde",
                "fechaHasta", "orden", "direccion", "q", "page", "size")) {
            assertTrue(java.util.stream.StreamSupport.stream(searchParameters.spliterator(), false)
                    .anyMatch(item -> item.path("name").asText().equals(parameter)),
                    "Falta parámetro OpenAPI " + parameter);
        }
        assertTrue(java.util.stream.StreamSupport.stream(searchParameters.spliterator(), false)
                .noneMatch(item -> item.path("name").asText().startsWith("X-User-")),
                "La búsqueda pública no debe requerir headers de identidad");
        assertEventErrors(searchOperation.path("responses"), "200", "400", "500");
        assertTrue(searchOperation.path("responses").path("401").isMissingNode());
        assertTrue(searchOperation.path("responses").path("403").isMissingNode());
        JsonNode detailOperation = paths.path("/eventos/{eventoId}").path("get");
        assertEventErrors(detailOperation.path("responses"), "200", "401", "404", "500");
        JsonNode detailParameters = detailOperation.path("parameters");
        for (String identityHeader : List.of("X-User-Id", "X-User-Roles")) {
            assertTrue(java.util.stream.StreamSupport.stream(detailParameters.spliterator(), false)
                    .anyMatch(item -> item.path("name").asText().equals(identityHeader)
                            && !item.path("required").asBoolean(true)),
                    "El header " + identityHeader + " debe ser opcional para el detalle público");
        }
        assertTrue(detailOperation.path("description").asText().contains("combina por sectorId"));
        assertTrue(detailOperation.path("description").asText().contains("si Reservas falla, la disponibilidad queda en null"));
        assertEventErrors(paths.path("/lugares").path("post").path("responses"), "400", "401", "403", "409", "500");
        assertEventErrors(paths.path("/lugares").path("get").path("responses"), "401", "403", "500");
        assertEventErrors(paths.path("/lugares/{id}").path("get").path("responses"), "401", "403", "404", "500");
        assertEventErrors(paths.path("/lugares/{id}/sectores").path("get").path("responses"), "401", "403", "404", "500");
        assertEventErrors(paths.path("/lugares/{id}/sectores").path("post").path("responses"), "400", "401", "403", "404", "409", "500");
        assertEventErrors(paths.path("/eventos").path("post").path("responses"), "400", "401", "403", "404", "409", "500");
        assertEventErrors(paths.path("/eventos/{eventoId}/sectores/{sectorId}/precio").path("put").path("responses"),
                "400", "401", "403", "404", "409", "500");
        assertEventErrors(paths.path("/eventos/{eventoId}/limite-entradas-por-cliente").path("put").path("responses"),
                "400", "401", "403", "404", "409", "500");

        JsonNode publicationResponses = paths.path("/eventos/{eventoId}/publicar").path("put").path("responses");
        assertEventErrors(publicationResponses, "400", "401", "403", "404", "409", "503", "500");
        assertTrue(publicationResponses.path("200").path("content").path("application/json")
                .path("examples").has("Evento publicado"));
        for (String action : java.util.List.of("suspender", "reactivar")) {
            JsonNode responses = paths.path("/eventos/{eventoId}/venta/" + action).path("put").path("responses");
            assertEventErrors(responses, "401", "403", "404", "409", "503", "500");
            assertTrue(responses.path("200").path("content").path("application/json").path("examples").size() > 0);
        }
        JsonNode cancellationResponses = paths.path("/eventos/{eventoId}/cancelar").path("put").path("responses");
        assertEventErrors(cancellationResponses, "401", "403", "404", "409", "503", "500");
        assertTrue(cancellationResponses.path("200").path("content").path("application/json")
                .path("examples").has("Evento cancelado"));
        assertTrue(cancellationResponses.path("409").path("description").asText().contains("cancelado"));
        assertTrue(cancellationResponses.path("409").path("description").asText().contains("finalizado"));
        assertTrue(cancellationResponses.path("503").path("description").asText().contains("pendiente"));
        assertTrue(cancellationResponses.path("503").path("description").asText().contains("no implica ausencia de efectos"));

        JsonNode callback = paths.path("/eventos/{eventoId}/disponibilidad").path("put");
        assertTrue(callback.path("description").asText().contains("No exige X-User-Id ni X-User-Roles"));
        assertTrue(callback.path("description").asText().contains("no se expone por el API Gateway"));
        assertTrue(callback.path("requestBody").path("content").path("application/json")
                .path("examples").has("Agotado"));
        assertEventErrors(callback.path("responses"), "400", "404", "409", "500");

        JsonNode internalEvent = paths.path("/internal/eventos/{eventoId}").path("get");
        assertTrue(internalEvent.path("description").asText().contains("no requiere X-User-Id ni X-User-Roles"));
        assertTrue(internalEvent.path("description").asText().contains("no se expone por el API Gateway"));
        JsonNode internalParameters = internalEvent.path("parameters");
        assertTrue(internalParameters.isMissingNode()
                || java.util.stream.StreamSupport.stream(internalParameters.spliterator(), false)
                        .noneMatch(parameter -> parameter.path("name").asText().startsWith("X-User-")),
                "El GET interno no debe documentar headers de usuario");
        assertEventErrors(internalEvent.path("responses"), "404", "500");
        assertTrue(internalEvent.path("responses").path("200").path("content").path("application/json")
                .path("examples").has("Evento para Compras"));
        JsonNode cancellationRequest = paths.path("/eventos/{eventoId}/cancelar").path("put").path("requestBody");
        assertTrue(cancellationRequest.path("content").has("application/json"));
        assertTrue(!cancellationRequest.path("required").asBoolean(false));
        assertTrue(cancellationRequest.path("content").path("application/json").path("examples")
                .has("Motivo"));

        assertTrue(paths.path("/eventos").path("post").path("requestBody").path("content")
                .path("application/json").path("examples").has("Evento en borrador"));
        assertTrue(paths.path("/eventos/{eventoId}/sectores/{sectorId}/precio").path("put")
                .path("requestBody").path("content").path("application/json").path("examples").has("Precio"));
        assertTrue(paths.path("/eventos/{eventoId}/limite-entradas-por-cliente").path("put")
                .path("requestBody").path("content").path("application/json").path("examples").has("Límite"));
    }

    @Test
    void documentaContratoInternoDePoliticaDeFila() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode api = new ObjectMapper().readTree(body);
        JsonNode paths = api.path("paths");
        JsonNode get = paths.path("/internal/eventos/{eventoId}").path("get");
        JsonNode put = paths.path("/internal/eventos/{eventoId}/politica-fila").path("put");
        assertTrue(get.path("description").asText().contains("sin caché"));
        assertTrue(put.path("description").asText().contains("cancelado"));
        assertTrue(put.path("description").asText().contains("desactivar"));
        for (JsonNode operation : List.of(get, put)) {
            assertTrue(java.util.stream.StreamSupport.stream(operation.path("parameters").spliterator(), false)
                    .noneMatch(parameter -> parameter.path("name").asText().startsWith("X-User-")));
        }

        assertEventErrors(put.path("responses"), "200", "400", "404", "409", "500");
        JsonNode request = put.path("requestBody");
        assertTrue(request.path("required").asBoolean());
        assertTrue(request.path("content").path("application/json").path("schema").path("$ref")
                .asText().endsWith("/PoliticaFilaRequest"));
        JsonNode schema = api.path("components").path("schemas").path("PoliticaFilaRequest");
        assertTrue(schema.path("properties").path("requiereFila").path("type").asText().equals("boolean"));
        assertTrue(java.util.stream.StreamSupport.stream(schema.path("required").spliterator(), false)
                .anyMatch(field -> field.asText().equals("requiereFila")));
        JsonNode example = get.path("responses").path("200").path("content").path("application/json")
                .path("examples").path("Evento para Compras").path("value");
        assertTrue(example.has("requiereFila") && example.has("fechaHora") && example.has("organizadorId"));
        assertTrue(example.path("sectores").get(0).has("tipo"));
    }

    @Test
    void documentaGestionYConsultaInternaDePersonal() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode paths = new ObjectMapper().readTree(body).path("paths");
        JsonNode gestion = paths.path("/eventos/{eventoId}/personal-acceso");
        for (String code : List.of("201", "400", "403", "404", "409", "503")) {
            assertTrue(gestion.path("post").path("responses").has(code));
        }
        for (String code : List.of("200", "403", "404")) {
            assertTrue(gestion.path("get").path("responses").has(code));
        }
        for (String code : List.of("204", "403", "404")) {
            assertTrue(paths.path("/eventos/{eventoId}/personal-acceso/{usuarioId}").path("delete")
                    .path("responses").has(code));
        }
        JsonNode interno = paths.path("/internal/eventos/{eventoId}/personal-acceso/{usuarioId}/autorizacion").path("get");
        assertTrue(interno.path("description").asText().contains("Sin caché"));
        assertTrue(java.util.stream.StreamSupport.stream(interno.path("parameters").spliterator(), false)
                .noneMatch(parameter -> parameter.path("name").asText().startsWith("X-User-")));
        assertTrue(interno.path("responses").has("200"));
    }

    private static void assertEventErrors(JsonNode responses, String... codes) {
        for (String code : codes) {
            JsonNode errorSchema = responses.path(code).path("content").path("application/json").path("schema");
            assertTrue(!errorSchema.isMissingNode(), "Falta el schema de error para HTTP " + code);
        }
    }
}
