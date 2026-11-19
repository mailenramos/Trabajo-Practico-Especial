package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.ticketya.gestionlugares.repository.PersonalAccesoRepository;

@SpringBootTest(properties = {"outbox.dispatcher.interval=3600000", "servicio.token=token-prueba"})
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
    @Autowired private MockMvc mockMvc;

    private static final Instant ASIGNACION = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant REVOCACION = Instant.parse("2030-01-02T10:00:00Z");
    private static final Instant REACTIVACION = Instant.parse("2030-01-03T10:00:00Z");

    @BeforeEach
    void limpiar() {
        repository.deleteAll();
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
