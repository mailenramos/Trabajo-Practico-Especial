package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketya.gestionlugares.domain.AsignacionPersonalAcceso;
import com.ticketya.gestionlugares.domain.EstadoPersonalAcceso;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.PersonalAccesoRepository;
import com.ticketya.gestionlugares.usuarios.http.HttpUsuariosClient;
import com.ticketya.gestionlugares.service.ResourceNotFoundException;
import com.ticketya.gestionlugares.service.ServiceUnavailableException;

@SpringBootTest(properties = {"outbox.dispatcher.interval=3600000", "servicio.token=token-prueba",
        "resilience4j.circuitbreaker.instances.usuarios.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.usuarios.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.usuarios.failure-rate-threshold=100",
        "resilience4j.circuitbreaker.instances.usuarios.wait-duration-in-open-state=2s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PersonalAccesoIntegrationTest {

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl()
                .replace("/test", "/ticketya_personal_acceso_test"));
    }

    @Autowired private PersonalAccesoRepository repository;
    @Autowired private EventoRepository eventos;
    @Autowired private MockMvc mockMvc;
    @Autowired private io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitos;
    @MockBean private HttpUsuariosClient usuarios;

    private static final String USUARIO_ID = "b06507ba-403b-4ce2-a828-72845ff212ad";

    private static final Instant ASIGNACION = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant REVOCACION = Instant.parse("2030-01-02T10:00:00Z");
    private static final Instant REACTIVACION = Instant.parse("2030-01-03T10:00:00Z");

    @BeforeEach
    void limpiar() {
        circuitos.circuitBreaker("usuarios").reset();
        circuitos.circuitBreaker("reservas").reset();
        repository.deleteAll();
        eventos.deleteAll();
        reset(usuarios);
    }

    @Test
    void circuitoAbiertoImpideAsignarOReactivarYSeRecupera() throws Exception {
        guardarEvento();
        repository.insert(nueva("evento-1", USUARIO_ID));
        repository.revocarSiActiva("evento-1", USUARIO_ID, REVOCACION);
        when(usuarios.consultarPorCorreo("personal@example.com"))
                .thenThrow(new ServiceUnavailableException("timeout"))
                .thenThrow(new ServiceUnavailableException("timeout"))
                .thenReturn(new HttpUsuariosClient.Usuario(USUARIO_ID, List.of("PERSONAL_ACCESO"), true));
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(alta("evento-1", "admin-1", "ADMIN", "{\"correo\":\"personal@example.com\"}"))
                    .andExpect(status().isServiceUnavailable());
        }
        var circuito = circuitos.circuitBreaker("usuarios");
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, circuito.getState());
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED,
                circuitos.circuitBreaker("reservas").getState());
        verify(usuarios, times(2)).consultarPorCorreo("personal@example.com");
        assertEquals(1, repository.count());
        assertEquals(EstadoPersonalAcceso.REVOCADA,
                repository.findByEventoIdAndUsuarioId("evento-1", USUARIO_ID).orElseThrow().getEstado());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            mockMvc.perform(alta("evento-1", "admin-1", "ADMIN", "{\"correo\":\"personal@example.com\"}"))
                    .andExpect(status().isCreated());
        });
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, circuito.getState());
        verify(usuarios, times(3)).consultarPorCorreo("personal@example.com");
        assertEquals(EstadoPersonalAcceso.ACTIVA,
                repository.findByEventoIdAndUsuarioId("evento-1", USUARIO_ID).orElseThrow().getEstado());
    }

    @Test
    void indiceUnicoIncluyeActivasYRevocadasYPuedeAsignarseAOtroEvento() {
        AsignacionPersonalAcceso original = repository.insert(nueva("evento-1", "usuario-1"));
        assertNotNull(original.getId());
        assertThrows(DuplicateKeyException.class, () -> repository.insert(nueva("evento-1", "usuario-1")));
        assertTrue(repository.revocarSiActiva("evento-1", "usuario-1", REVOCACION).isPresent());
        assertThrows(DuplicateKeyException.class, () -> repository.insert(nueva("evento-1", "usuario-1")));
        repository.insert(nueva("evento-2", "usuario-1"));
        assertEquals(2, repository.count());
    }

    @Test
    void altaConcurrenteSoloInsertaUnDocumento() throws Exception {
        List<Boolean> resultados = ejecutarConcurrentes(() -> {
            try {
                repository.insert(nueva("evento-1", "usuario-1"));
                return true;
            } catch (DuplicateKeyException exception) {
                return false;
            }
        });
        assertEquals(1, resultados.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, repository.count());
    }

    @Test
    void revocacionEsIdempotenteYReactivacionConcurrenteActualizaUnSoloRegistro() throws Exception {
        AsignacionPersonalAcceso original = repository.insert(nueva("evento-1", "usuario-1"));
        assertTrue(repository.revocarSiActiva("evento-1", "usuario-1", REVOCACION).isPresent());
        assertTrue(repository.revocarSiActiva("evento-1", "usuario-1", REACTIVACION).isEmpty());
        AsignacionPersonalAcceso revocada = repository.findByEventoIdAndUsuarioId("evento-1", "usuario-1").orElseThrow();
        assertEquals(EstadoPersonalAcceso.REVOCADA, revocada.getEstado());
        assertEquals(ASIGNACION, revocada.getAsignadaEn());
        assertEquals(REVOCACION, revocada.getRevocadaEn());

        List<Boolean> resultados = ejecutarConcurrentes(
                () -> repository.reactivarSiRevocada("evento-1", "usuario-1", REACTIVACION).isPresent());
        assertEquals(1, resultados.stream().filter(Boolean::booleanValue).count());
        AsignacionPersonalAcceso activa = repository.findByEventoIdAndUsuarioId("evento-1", "usuario-1").orElseThrow();
        assertEquals(original.getId(), activa.getId());
        assertEquals(EstadoPersonalAcceso.ACTIVA, activa.getEstado());
        assertEquals(REACTIVACION, activa.getAsignadaEn());
        assertNull(activa.getRevocadaEn());
        assertEquals(1, repository.count());
    }

    @Test
    void autorizacionConsultaEstadoActualSinHeadersDeUsuario() throws Exception {
        consultar("evento-inexistente", "usuario-1", false);
        repository.insert(nueva("evento-1", "usuario-1"));
        consultar("evento-1", "usuario-1", true);
        consultar("evento-1", "usuario-2", false);
        consultar("evento-2", "usuario-1", false);
        assertTrue(repository.revocarSiActiva("evento-1", "usuario-1", REVOCACION).isPresent());
        consultar("evento-1", "usuario-1", false);
        assertTrue(repository.reactivarSiRevocada("evento-1", "usuario-1", REACTIVACION).isPresent());
        consultar("evento-1", "usuario-1", true);
    }

    @Test
    void gestionaAsignacionYRevocacionConEfectoInmediato() throws Exception {
        guardarEvento();
        when(usuarios.consultarPorCorreo("personal@example.com"))
                .thenReturn(new HttpUsuariosClient.Usuario(USUARIO_ID, List.of("CLIENTE", "PERSONAL_ACCESO"), true));
        mockMvc.perform(alta("evento-1", "organizador-1", "CLIENTE,ORGANIZADOR", "{\"correo\":\"personal@example.com\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.usuarioId").value(USUARIO_ID))
                .andExpect(jsonPath("$.estado").value("ACTIVA"));
        consultar("evento-1", USUARIO_ID, true);
        mockMvc.perform(alta("evento-1", "admin-1", "CLIENTE,ADMIN", "{\"correo\":\"personal@example.com\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/eventos/evento-1/personal-acceso")
                .header("X-User-Id", "admin-1").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].usuarioId").value(USUARIO_ID));
        mockMvc.perform(baja("evento-1", USUARIO_ID, "organizador-1", "ORGANIZADOR"))
                .andExpect(status().isNoContent());
        Instant revocadaEn = repository.findByEventoIdAndUsuarioId("evento-1", USUARIO_ID).orElseThrow().getRevocadaEn();
        consultar("evento-1", USUARIO_ID, false);
        mockMvc.perform(baja("evento-1", USUARIO_ID, "admin-1", "ADMIN"))
                .andExpect(status().isNoContent());
        assertEquals(revocadaEn, repository.findByEventoIdAndUsuarioId("evento-1", USUARIO_ID).orElseThrow().getRevocadaEn());
        mockMvc.perform(alta("evento-1", "admin-1", "ADMIN", "{\"correo\":\"personal@example.com\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("ACTIVA"))
                .andExpect(jsonPath("$.revocadaEn").doesNotExist());
        assertEquals(1, repository.count());
        consultar("evento-1", USUARIO_ID, true);
        mockMvc.perform(baja("evento-1", "ausente", "admin-1", "ADMIN")).andExpect(status().isNotFound());
    }

    @Test
    void deniegaIdentidadesInsuficientesYEventoInexistente() throws Exception {
        guardarEvento();
        mockMvc.perform(alta("evento-1", "otro", "ORGANIZADOR", "{\"correo\":\"personal@example.com\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/eventos/evento-1/personal-acceso").header("X-User-Id", "organizador-1")
                .header("X-User-Roles", "CLIENTE")).andExpect(status().isForbidden());
        mockMvc.perform(baja("evento-1", USUARIO_ID, "otro", "ORGANIZADOR")).andExpect(status().isForbidden());
        mockMvc.perform(alta("evento-inexistente", "admin-1", "ADMIN", "{\"correo\":\"personal@example.com\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(alta("evento-1", "admin-1", "ADMIN", "{\"correo\":\"no-es-correo\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(usuarios);
        assertEquals(0, repository.count());
    }

    @Test
    void fallasDeMs6NoCreanNiReactivanAsignaciones() throws Exception {
        guardarEvento();
        when(usuarios.consultarPorCorreo("personal@example.com"))
                .thenThrow(new ServiceUnavailableException("Servicio de usuarios no disponible"));
        mockMvc.perform(alta("evento-1", "admin-1", "ADMIN", "{\"correo\":\"personal@example.com\"}"))
                .andExpect(status().isServiceUnavailable());
        assertEquals(0, repository.count());
        reset(usuarios);
        repository.insert(nueva("evento-1", USUARIO_ID));
        repository.revocarSiActiva("evento-1", USUARIO_ID, REVOCACION);
        when(usuarios.consultarPorCorreo("personal@example.com"))
                .thenReturn(new HttpUsuariosClient.Usuario(USUARIO_ID, List.of("PERSONAL_ACCESO"), false))
                .thenReturn(new HttpUsuariosClient.Usuario(USUARIO_ID, List.of("CLIENTE"), true))
                .thenThrow(new ResourceNotFoundException("Usuario no encontrado"))
                .thenThrow(new ServiceUnavailableException("Servicio de usuarios no disponible"));
        for (int esperado : List.of(400, 400, 404, 503)) {
            mockMvc.perform(alta("evento-1", "admin-1", "ADMIN", "{\"correo\":\"personal@example.com\"}"))
                    .andExpect(status().is(esperado));
            var asignacion = repository.findByEventoIdAndUsuarioId("evento-1", USUARIO_ID).orElseThrow();
            assertEquals(EstadoPersonalAcceso.REVOCADA, asignacion.getEstado());
            assertEquals(REVOCACION, asignacion.getRevocadaEn());
            assertEquals(1, repository.count());
        }
    }

    private void guardarEvento() {
        Evento evento = Evento.borrador("organizador-1", "lugar-1", "CABA", "Evento",
                "Descripción", "MUSICA", List.of(), Instant.parse("2030-01-01T10:00:00Z"), 4, List.of());
        evento.setId("evento-1");
        eventos.save(evento);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder alta(
            String eventoId, String user, String roles, String body) {
        return post("/eventos/{eventoId}/personal-acceso", eventoId).header("X-User-Id", user)
                .header("X-User-Roles", roles).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder baja(
            String eventoId, String usuarioId, String user, String roles) {
        return delete("/eventos/{eventoId}/personal-acceso/{usuarioId}", eventoId, usuarioId)
                .header("X-User-Id", user).header("X-User-Roles", roles);
    }

    private void consultar(String eventoId, String usuarioId, boolean autorizado) throws Exception {
        mockMvc.perform(get("/internal/eventos/{eventoId}/personal-acceso/{usuarioId}/autorizacion", eventoId, usuarioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventoId").value(eventoId))
                .andExpect(jsonPath("$.usuarioId").value(usuarioId))
                .andExpect(jsonPath("$.autorizado").value(autorizado));
    }

    private AsignacionPersonalAcceso nueva(String eventoId, String usuarioId) {
        return new AsignacionPersonalAcceso(eventoId, usuarioId, ASIGNACION);
    }

    private List<Boolean> ejecutarConcurrentes(java.util.concurrent.Callable<Boolean> tarea) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch iniciar = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> coordinada = () -> {
                listos.countDown();
                if (!iniciar.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("No se liberó la ejecución concurrente");
                }
                return tarea.call();
            };
            Future<Boolean> primero = executor.submit(coordinada);
            Future<Boolean> segundo = executor.submit(coordinada);
            assertTrue(listos.await(10, TimeUnit.SECONDS));
            iniciar.countDown();
            return List.of(primero.get(15, TimeUnit.SECONDS), segundo.get(15, TimeUnit.SECONDS));
        } finally {
            iniciar.countDown();
            executor.shutdownNow();
        }
    }
}
