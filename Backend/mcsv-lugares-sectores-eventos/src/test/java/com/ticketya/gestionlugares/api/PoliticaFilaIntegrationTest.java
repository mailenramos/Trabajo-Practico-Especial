package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.service.ConflictException;
import com.ticketya.gestionlugares.service.EventoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"outbox.dispatcher.interval=3600000", "servicio.token=token-prueba"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PoliticaFilaIntegrationTest {

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl()
                .replace("/test", "/ticketya_lugares"));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private TestRestTemplate http;
    @Autowired private EventoRepository repository;
    @Autowired private MongoTemplate mongo;
    @Autowired private EventoService service;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void limpiar() {
        repository.deleteAll();
    }

    @Test
    void consultaDatosActualesYConservaContratoDeCompras() throws Exception {
        Evento evento = guardar(EstadoEvento.BORRADOR, false);
        assertFalse(evento.isRequiereFila());
        mockMvc.perform(get("/internal/eventos/{id}", evento.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventoId").value(evento.getId()))
                .andExpect(jsonPath("$.nombre").value("Festival"))
                .andExpect(jsonPath("$.tipo").value("MUSICA"))
                .andExpect(jsonPath("$.ciudad").value("CABA"))
                .andExpect(jsonPath("$.organizadorId").value("org-1"))
                .andExpect(jsonPath("$.fechaHora").value("2030-01-02T03:04:05Z"))
                .andExpect(jsonPath("$.fechaInicio").value("2030-01-02T03:04:05Z"))
                .andExpect(jsonPath("$.estado").value("borrador"))
                .andExpect(jsonPath("$.requiereFila").value(false))
                .andExpect(jsonPath("$.sectores[0].sectorId").value("num"))
                .andExpect(jsonPath("$.sectores[0].nombre").value("Platea"))
                .andExpect(jsonPath("$.sectores[0].tipo").value("NUMERADO"))
                .andExpect(jsonPath("$.sectores[0].filas").value(2))
                .andExpect(jsonPath("$.sectores[0].asientosPorFila").value(3))
                .andExpect(jsonPath("$.sectores[0].capacidad").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.sectores[0].precio").value(150))
                .andExpect(jsonPath("$.sectores[1].tipo").value("NO_NUMERADO"))
                .andExpect(jsonPath("$.sectores[1].filas").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.sectores[1].asientosPorFila").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.sectores[1].capacidad").value(100))
                .andExpect(jsonPath("$.sectores[1].precio").value(org.hamcrest.Matchers.nullValue()));

        cambiar(evento.getId(), "{\"requiereFila\":true}").andExpect(status().isOk());
        mockMvc.perform(get("/internal/eventos/{id}", evento.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requiereFila").value(true));
        mockMvc.perform(get("/internal/eventos/{id}", "000000000000000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void activaDesactivaYRepiteSinEscrituras() throws Exception {
        Evento evento = guardar(EstadoEvento.PUBLICADO, false);
        long versionInicial = evento.getVersion();
        cambiar(evento.getId(), "{\"requiereFila\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.eventoId").value(evento.getId()))
                .andExpect(jsonPath("$.requiereFila").value(true));
        assertEquals(versionInicial + 1, repository.findById(evento.getId()).orElseThrow().getVersion());
        Document antes = documento(evento.getId());
        cambiar(evento.getId(), "{\"requiereFila\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.requiereFila").value(true));
        assertEquals(antes, documento(evento.getId()));
        cambiar(evento.getId(), "{\"requiereFila\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.requiereFila").value(false));
        assertEquals(versionInicial + 2, repository.findById(evento.getId()).orElseThrow().getVersion());
        antes = documento(evento.getId());
        cambiar(evento.getId(), "{\"requiereFila\":false}").andExpect(status().isOk());
        assertEquals(antes, documento(evento.getId()));
    }

    @Test
    void canceladoRechazaActivarYPermiteDesactivarSinEscriturasInnecesarias() throws Exception {
        Evento evento = guardar(EstadoEvento.CANCELADO, true);
        Document antes = documento(evento.getId());
        cambiar(evento.getId(), "{\"requiereFila\":true}").andExpect(status().isConflict());
        assertEquals(antes, documento(evento.getId()));
        cambiar(evento.getId(), "{\"requiereFila\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.requiereFila").value(false));
        Evento actualizado = repository.findById(evento.getId()).orElseThrow();
        assertEquals(evento.getVersion() + 1, actualizado.getVersion());
        assertFalse(actualizado.isRequiereFila());
        antes = documento(evento.getId());
        cambiar(evento.getId(), "{\"requiereFila\":false}").andExpect(status().isOk());
        cambiar(evento.getId(), "{\"requiereFila\":true}").andExpect(status().isConflict());
        assertEquals(antes, documento(evento.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BORRADOR", "CANCELADO"})
    void documentoAntiguoSinCampoSeLeeComoFalseYNoSeModifica(String estado) throws Exception {
        Evento evento = guardar(EstadoEvento.valueOf(estado), false);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(evento.getId())),
                new Update().unset("requiereFila"), Evento.class);
        Document antes = documento(evento.getId());
        assertFalse(antes.containsKey("requiereFila"));
        assertFalse(repository.findById(evento.getId()).orElseThrow().isRequiereFila());
        mockMvc.perform(get("/internal/eventos/{id}", evento.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requiereFila").value(false));
        cambiar(evento.getId(), "{\"requiereFila\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.requiereFila").value(false));
        assertEquals(antes, documento(evento.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "{}", "null", "[]", "{\"requiereFila\":null}",
            "{\"requiereFila\":\"true\"}", "{\"requiereFila\":1}", "{\"requiereFila\":true} false"})
    void validaExistenciaAntesDelBodyYBodyAntesDeCancelacion(String body) throws Exception {
        Evento cancelado = guardar(EstadoEvento.CANCELADO, false);
        Document antes = documento(cancelado.getId());
        cambiar("000000000000000000000000", body).andExpect(status().isNotFound());
        cambiar(cancelado.getId(), body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").exists());
        assertEquals(antes, documento(cancelado.getId()));
    }

    @Test
    void escrituraConVersionObsoletaNoSobrescribeCancelacionConcurrente() {
        Evento evento = guardar(EstadoEvento.PUBLICADO, false);
        Evento obsoleto = repository.findById(evento.getId()).orElseThrow();
        Evento concurrente = repository.findById(evento.getId()).orElseThrow();
        concurrente.setEstado(EstadoEvento.CANCELADO);
        repository.save(concurrente);

        assertThrows(ConflictException.class, () -> service.actualizarPoliticaFila(obsoleto, true));
        Evento persistido = repository.findById(evento.getId()).orElseThrow();
        assertEquals(EstadoEvento.CANCELADO, persistido.getEstado());
        assertFalse(persistido.isRequiereFila());
    }

    @Test
    void rutaNuevaRespondePorHttpReal() {
        Evento evento = guardar(EstadoEvento.BORRADOR, false);
        var response = http.exchange("/internal/eventos/{id}/politica-fila", HttpMethod.PUT,
                new HttpEntity<>(Map.of("requiereFila", true)), Map.class, evento.getId());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().get("requiereFila"));
        assertTrue(repository.findById(evento.getId()).orElseThrow().isRequiereFila());
    }

    @Test
    void requestsDePostmanDePoliticaCoincidenConElServicio() throws Exception {
        Evento evento = guardar(EstadoEvento.BORRADOR, false);
        JsonNode collection = objectMapper.readTree(Path.of("postman",
                "TicketYa - Eventos.postman_collection.json").toFile());
        JsonNode folder = java.util.stream.StreamSupport.stream(collection.path("item").spliterator(), false)
                .filter(item -> item.path("name").asText().equals("20 Política de fila"))
                .findFirst().orElseThrow();
        assertEquals(6, folder.path("item").size());
        int[] statuses = {200, 200, 200, 200, 400, 404};
        for (int i = 0; i < statuses.length; i++) {
            JsonNode request = folder.path("item").get(i).path("request");
            assertEquals("noauth", request.path("auth").path("type").asText());
            String url = request.path("url").path("raw").asText()
                    .replace("{{baseUrl}}", "").replace("{{eventoCompraId}}", evento.getId());
            HttpHeaders headers = new HttpHeaders();
            if (request.has("body")) headers.setContentType(MediaType.APPLICATION_JSON);
            String requestBody = request.has("body") ? request.path("body").path("raw").asText() : null;
            var response = http.exchange(url, HttpMethod.valueOf(request.path("method").asText()),
                    new HttpEntity<>(requestBody, headers), String.class);
            assertEquals(statuses[i], response.getStatusCode().value(), request.path("url").toString());
            JsonNode result = objectMapper.readTree(response.getBody());
            if (i == 0 || i == 2 || i == 3) {
                assertEquals(evento.getId(), result.path("eventoId").asText());
                assertEquals(i != 3, result.path("requiereFila").asBoolean());
            } else if (i == 1) {
                for (String key : List.of("eventoId", "nombre", "tipo", "fechaHora", "fechaInicio",
                        "ciudad", "estado", "organizadorId", "requiereFila", "sectores")) {
                    assertTrue(result.has(key), key);
                }
                assertEquals(result.path("fechaHora").asText(), result.path("fechaInicio").asText());
                assertTrue(result.path("requiereFila").asBoolean());
                for (JsonNode sector : result.path("sectores")) {
                    for (String key : List.of("sectorId", "nombre", "tipo", "filas",
                            "asientosPorFila", "capacidad", "precio")) {
                        assertTrue(sector.has(key), key);
                    }
                }
            } else if (i == 4) {
                for (String key : List.of("timestamp", "status", "error", "mensaje")) {
                    assertTrue(result.has(key), key);
                }
            }
        }
    }

    private Evento guardar(EstadoEvento estado, boolean requiereFila) {
        Evento evento = Evento.borrador("org-1", "lugar-1", "CABA", "Festival", "Desc", "MUSICA",
                List.of("Artista"), Instant.parse("2030-01-02T03:04:05Z"), 4, List.of(
                        new EventoSector("num", "Platea", TipoSector.NUMERADO, 2, 3, null, new BigDecimal("150")),
                        new EventoSector("no-num", "Campo", TipoSector.NO_NUMERADO, null, null, 100, null)));
        evento.setEstado(estado);
        if (requiereFila) evento.setRequiereFila(true);
        return repository.save(evento);
    }

    private Document documento(String id) {
        return mongo.findById(id, Document.class, "eventos");
    }

    private org.springframework.test.web.servlet.ResultActions cambiar(String id, String body) throws Exception {
        return mockMvc.perform(put("/internal/eventos/{id}/politica-fila", id)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
