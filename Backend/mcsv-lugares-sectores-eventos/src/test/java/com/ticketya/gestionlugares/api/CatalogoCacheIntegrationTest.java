package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.benmanes.caffeine.cache.Ticker;
import com.ticketya.gestionlugares.config.CatalogoCacheConfiguration;
import com.ticketya.gestionlugares.domain.AsignacionPersonalAcceso;
import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.outbox.OutboxDispatcher;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.LugarRepository;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;
import com.ticketya.gestionlugares.repository.PersonalAccesoRepository;
import com.ticketya.gestionlugares.reservas.ReservasClient;
import com.ticketya.gestionlugares.service.BusquedaCatalogo;
import com.ticketya.gestionlugares.service.CatalogoCacheInvalidador;
import com.ticketya.gestionlugares.service.EventoLifecycleService;
import com.ticketya.gestionlugares.service.EventoService;

@SpringBootTest(properties = {"outbox.dispatcher.interval=3600000", "eventos.finalizacion.interval=3600000",
        "servicio.token=token-prueba", "catalogo.cache.ttl=30s", "catalogo.cache.maximo-entradas=3"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CatalogoCacheIntegrationTest {

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");
    private static final AtomicLong TIEMPO = new AtomicLong();

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl()
                .replace("/test", "/ticketya_catalogo_cache_test"));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private EventoRepository eventos;
    @Autowired private LugarRepository lugares;
    @Autowired private PersonalAccesoRepository personal;
    @Autowired private OutboxAvisoRepository outbox;
    @Autowired private EventoLifecycleService lifecycle;
    @Autowired private EventoService eventoService;
    @Autowired private CatalogoCacheInvalidador invalidador;
    @Autowired private CaffeineCacheManager cacheManager;
    @Autowired private TransactionTemplate transaccion;
    @SpyBean private MongoTemplate mongo;
    @MockBean private ReservasClient reservas;
    @MockBean private OutboxDispatcher dispatcher;
    @MockBean private Ticker ticker;

    @BeforeEach
    void preparar() {
        when(ticker.read()).thenAnswer(invocation -> TIEMPO.get());
        eventos.deleteAll();
        lugares.deleteAll();
        personal.deleteAll();
        outbox.deleteAll();
        cache().clear();
        clearInvocations(mongo);
    }

    @Test
    void reutilizaBusquedaNormalizadaYDistingueTodosLosParametros() throws Exception {
        buscar().andExpect(status().isOk()).andExpect(jsonPath("$.totalElementos").value(0));
        mockMvc.perform(get("/eventos").param("page", "0").param("size", "20")
                        .param("orden", "FECHA").param("direccion", "ASC"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/eventos").header("X-User-Id", "otro").header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElementos").value(0));
        verificarLecturas(1);

        String[][] variantes = {
                {"tipo", "MUSICA"}, {"organizadorId", "org-1"}, {"participante", "artista"},
                {"ciudad", "CABA"}, {"fechaDesde", "2030-01-01T00:00:00Z"},
                {"fechaHasta", "2030-01-01T00:00:00Z"}, {"orden", "precio"},
                {"direccion", "desc"}, {"q", "festival"}, {"page", "1"}, {"size", "1"}
        };
        for (String[] variante : variantes) {
            mockMvc.perform(get("/eventos").param(variante[0], variante[1])).andExpect(status().isOk());
        }
        verificarLecturas(12);
        mockMvc.perform(get("/eventos").param("page", "-1")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/eventos").param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/eventos").param("orden", "invalid")).andExpect(status().isBadRequest());
        verificarLecturas(12);
    }

    @Test
    void noCacheaErroresDeLectura() throws Exception {
        doThrow(new RuntimeException("fallo de lectura")).doCallRealMethod()
                .when(mongo).count(any(Query.class), eq(Evento.class));
        mockMvc.perform(get("/eventos")).andExpect(status().isInternalServerError());
        buscar().andExpect(jsonPath("$.totalElementos").value(0));
        verify(mongo, times(2)).count(any(Query.class), eq(Evento.class));
        verify(mongo, times(1)).find(any(Query.class), eq(Evento.class));
    }

    @Test
    void expiraAlTtlYRespetaElTamanoMaximo() throws Exception {
        buscar().andExpect(status().isOk());
        TIEMPO.addAndGet(Duration.ofSeconds(29).toNanos());
        buscar().andExpect(status().isOk());
        verificarLecturas(1);
        TIEMPO.addAndGet(Duration.ofSeconds(1).toNanos());
        buscar().andExpect(status().isOk());
        verificarLecturas(2);

        for (int page = 1; page <= 6; page++) {
            mockMvc.perform(get("/eventos").param("page", Integer.toString(page))).andExpect(status().isOk());
        }
        var nativeCache = cache().getNativeCache();
        nativeCache.cleanUp();
        assertTrue(nativeCache.estimatedSize() <= 3);
        int desalojada = -1;
        for (int page = 0; page <= 6; page++) {
            var clave = new BusquedaCatalogo(null, null, null, null, null, null, "fecha", "asc", null, page, 20);
            if (nativeCache.getIfPresent(clave) == null) {
                desalojada = page;
                break;
            }
        }
        assertTrue(desalojada >= 0);
        mockMvc.perform(get("/eventos").param("page", Integer.toString(desalojada))).andExpect(status().isOk());
        verificarLecturas(9);
    }

    @Test
    void invalidaPublicacionVentaAgotamientoYCancelacion() throws Exception {
        Evento evento = eventos.insert(borrador(Instant.parse("2035-01-01T00:00:00Z")));
        buscar().andExpect(jsonPath("$.totalElementos").value(0));
        lifecycle.publicar(evento.getId(), "org-1", "ORGANIZADOR");
        buscar().andExpect(jsonPath("$.contenido[0].estado").value("publicado"));
        lifecycle.suspenderVenta(evento.getId(), "admin", "ADMIN");
        buscar().andExpect(jsonPath("$.contenido[0].ventaSuspendida").value(true));
        lifecycle.reactivarVenta(evento.getId(), "admin", "ADMIN");
        buscar().andExpect(jsonPath("$.contenido[0].ventaSuspendida").value(false));
        lifecycle.aplicarDisponibilidad(evento.getId(), true);
        buscar().andExpect(jsonPath("$.contenido[0].estado").value("agotado"));
        lifecycle.aplicarDisponibilidad(evento.getId(), false);
        buscar().andExpect(jsonPath("$.contenido[0].estado").value("publicado"));
        lifecycle.cancelar(evento.getId(), "admin", "ADMIN");
        buscar().andExpect(jsonPath("$.totalElementos").value(0));
        verificarLecturas(7);
    }

    @Test
    void invalidaFinalizacionYPermiteInvalidacionDeFuturasEscrituras() throws Exception {
        Evento evento = borrador(Instant.parse("2020-01-01T00:00:00Z"));
        evento.definirPrecioMinimoYPublicar();
        eventos.insert(evento);
        buscar().andExpect(jsonPath("$.totalElementos").value(1));
        lifecycle.finalizarVencidos();
        buscar().andExpect(jsonPath("$.totalElementos").value(0));

        evento = eventos.findById(evento.getId()).orElseThrow();
        evento.setEstado(EstadoEvento.PUBLICADO);
        eventos.save(evento);
        invalidador.invalidar();
        buscar().andExpect(jsonPath("$.totalElementos").value(1));
        evento = eventos.findById(evento.getId()).orElseThrow();
        evento.setNombre("Nombre modificado");
        eventos.save(evento);
        invalidador.invalidar();
        buscar().andExpect(jsonPath("$.contenido[0].nombre").value("Nombre modificado"));
        verificarLecturas(4);
    }

    @Test
    void invalidaSoloDespuesDelCommitYNoEnRollback() throws Exception {
        Evento evento = borrador(Instant.parse("2035-01-01T00:00:00Z"));
        evento.definirPrecioMinimoYPublicar();
        eventos.insert(evento);
        buscar().andExpect(jsonPath("$.contenido[0].ventaSuspendida").value(false));
        transaccion.executeWithoutResult(status -> {
            eventoService.suspenderVentaLocal(evento.getId());
            assertNotNull(cache().get(new BusquedaCatalogo(null, null, null, null, null, null,
                    "fecha", "asc", null, 0, 20)));
        });
        buscar().andExpect(jsonPath("$.contenido[0].ventaSuspendida").value(true));
        verificarLecturas(2);

        transaccion.executeWithoutResult(status -> {
            eventoService.reactivarVentaLocal(evento.getId());
            status.setRollbackOnly();
        });
        buscar().andExpect(jsonPath("$.contenido[0].ventaSuspendida").value(true));
        verificarLecturas(2);

        Evento draft = eventos.insert(borrador(Instant.parse("2035-01-02T00:00:00Z")));
        AvisoOutbox duplicado = new AvisoOutbox();
        duplicado.setId("publicacion-" + draft.getId());
        outbox.insert(duplicado);
        assertThrows(DuplicateKeyException.class, () -> lifecycle.publicar(draft.getId(), "org-1", "ORGANIZADOR"));
        assertEquals(EstadoEvento.BORRADOR, eventos.findById(draft.getId()).orElseThrow().getEstado());
        buscar().andExpect(jsonPath("$.totalElementos").value(1));
        verificarLecturas(2);
    }

    @Test
    void consultasInternasYDetalleNoUsanCache() throws Exception {
        Lugar lugar = lugares.insert(new Lugar(null, "Sala", "Calle 1", "CABA", 0, 0, List.of()));
        Evento evento = borrador(Instant.parse("2035-01-01T00:00:00Z"));
        evento.setLugarId(lugar.getId());
        evento.definirPrecioMinimoYPublicar();
        eventos.insert(evento);
        personal.insert(new AsignacionPersonalAcceso(evento.getId(), "usuario", Instant.now()));
        buscar().andExpect(status().isOk());

        mockMvc.perform(get("/eventos/{id}", evento.getId())).andExpect(status().isOk());
        mockMvc.perform(get("/eventos/{id}", evento.getId())).andExpect(status().isOk());
        verify(reservas, times(2)).consultarDisponibilidad(evento.getId());
        mockMvc.perform(get("/internal/eventos/{id}", evento.getId()))
                .andExpect(jsonPath("$.requiereFila").value(false));
        eventoService.actualizarPoliticaFila(eventos.findById(evento.getId()).orElseThrow(), true);
        mockMvc.perform(get("/internal/eventos/{id}", evento.getId()))
                .andExpect(jsonPath("$.requiereFila").value(true));
        mockMvc.perform(get("/internal/eventos/{id}/personal-acceso/usuario/autorizacion", evento.getId()))
                .andExpect(jsonPath("$.autorizado").value(true));
        personal.revocarSiActiva(evento.getId(), "usuario", Instant.now());
        mockMvc.perform(get("/internal/eventos/{id}/personal-acceso/usuario/autorizacion", evento.getId()))
                .andExpect(jsonPath("$.autorizado").value(false));
    }

    private ResultActions buscar() throws Exception {
        return mockMvc.perform(get("/eventos")).andExpect(status().isOk());
    }

    private void verificarLecturas(int cantidad) {
        verify(mongo, times(cantidad)).count(any(Query.class), eq(Evento.class));
        verify(mongo, times(cantidad)).find(any(Query.class), eq(Evento.class));
    }

    private CaffeineCache cache() {
        return (CaffeineCache) cacheManager.getCache(CatalogoCacheConfiguration.BUSQUEDA_EVENTOS);
    }

    private Evento borrador(Instant fecha) {
        return Evento.borrador("org-1", "lugar-1", "CABA", "Festival", "Descripción", "MUSICA",
                List.of("Artista"), fecha, 4,
                List.of(new EventoSector("sector-1", "Campo", TipoSector.NO_NUMERADO, null, null, 100,
                        new BigDecimal("100"))));
    }
}
