package com.ticketya.gestionlugares.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.LugarRepository;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;
import com.ticketya.gestionlugares.reservas.ReservasClient;
import com.ticketya.gestionlugares.reservas.ReservasClientException;
import com.ticketya.gestionlugares.reservas.dto.AsientoDisponibleResponse;
import com.ticketya.gestionlugares.reservas.dto.DisponibilidadEventoResponse;
import com.ticketya.gestionlugares.reservas.dto.SectorDisponibilidadResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"spring.data.mongodb.auto-index-creation=false", "outbox.dispatcher.interval=3600000",
        "resilience4j.circuitbreaker.instances.reservas.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.reservas.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.reservas.failure-rate-threshold=100"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class EventoDetalleDisponibilidadIntegrationTest {
    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl().replace("/test", "/ticketya_lugares"));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private EventoRepository eventoRepository;
    @Autowired private LugarRepository lugarRepository;
    @Autowired private OutboxAvisoRepository outboxRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitos;
    @Autowired private org.springframework.boot.actuate.health.HealthContributorRegistry healthRegistry;
    @MockBean private ReservasClient reservasClient;

    @BeforeEach
    void limpiarBase() {
        circuitos.circuitBreaker("reservas").reset();
        circuitos.circuitBreaker("usuarios").reset();
        eventoRepository.deleteAll();
        lugarRepository.deleteAll();
        outboxRepository.deleteAll();
    }

    @Test
    void circuitoAbiertoDegradaDetallePublicacionYSuspensionSinNuevasLlamadas() throws Exception {
        Lugar lugar = crearLugar();
        Evento publicado = crearEvento(lugar, EstadoEvento.PUBLICADO);
        Evento borrador = crearEvento(lugar, EstadoEvento.BORRADOR);
        when(reservasClient.consultarDisponibilidad(publicado.getId()))
                .thenThrow(new ReservasClientException("timeout"));

        for (int intento = 0; intento < 3; intento++) {
            mockMvc.perform(get("/eventos/{id}", publicado.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sectores[0].disponibilidad").value(org.hamcrest.Matchers.nullValue()));
        }
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN,
                circuitos.circuitBreaker("reservas").getState());
        verify(reservasClient, org.mockito.Mockito.times(2)).consultarDisponibilidad(publicado.getId());

        mockMvc.perform(put("/eventos/{id}/publicar", borrador.getId())
                        .header("X-User-Id", "org-1").header("X-User-Roles", "ORGANIZADOR"))
                .andExpect(status().isServiceUnavailable());
        for (String operacion : List.of("suspender", "reactivar")) {
            mockMvc.perform(put("/eventos/{id}/venta/{operacion}", publicado.getId(), operacion)
                            .header("X-User-Id", "admin-1").header("X-User-Roles", "ADMIN"))
                    .andExpect(status().isServiceUnavailable());
        }
        verify(reservasClient, org.mockito.Mockito.never()).inicializarDisponibilidad(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(reservasClient, org.mockito.Mockito.never()).cambiarEstadoVenta(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertEquals(EstadoEvento.BORRADOR, eventoRepository.findById(borrador.getId()).orElseThrow().getEstado());
        Evento sinCambios = eventoRepository.findById(publicado.getId()).orElseThrow();
        assertEquals(publicado.getVersion(), sinCambios.getVersion());
        assertEquals(publicado.isVentaSuspendida(), sinCambios.isVentaSuspendida());
    }

    @Test
    void conflictosRemotosNoSeRegistranEnLaVentana() throws Exception {
        Evento evento = guardarEventoPublicado();
        org.mockito.Mockito.doThrow(new com.ticketya.gestionlugares.reservas.ReservasConflictException("409"))
                .when(reservasClient).cambiarEstadoVenta(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        for (int intento = 0; intento < 3; intento++) {
            mockMvc.perform(put("/eventos/{id}/venta/suspender", evento.getId())
                            .header("X-User-Id", "admin-1").header("X-User-Roles", "ADMIN"))
                    .andExpect(status().isConflict());
        }

        var circuito = circuitos.circuitBreaker("reservas");
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, circuito.getState());
        assertEquals(0, circuito.getMetrics().getNumberOfBufferedCalls());
        verify(reservasClient, org.mockito.Mockito.times(3)).cambiarEstadoVenta(
                evento.getId(), com.ticketya.gestionlugares.reservas.EstadoVentaReservas.SUSPENDIDA);
    }

    @Test
    void healthEstandarConCircuitoAbiertoMantieneUp() throws Exception {
        circuitos.circuitBreaker("reservas").transitionToOpenState();
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.circuitBreakers.status").value("UP"))
                .andExpect(jsonPath("$.components.circuitBreakers.details").doesNotExist())
                .andExpect(jsonPath("$.components.circuitbreakers.components.reservas.status").value("OPEN"))
                .andExpect(jsonPath("$.components.circuitbreakers.components.usuarios.status").value("CLOSED"))
                .andExpect(jsonPath("$.components.circuitbreakers.components.reservas.details").doesNotExist());

        circuitos.circuitBreaker("usuarios").transitionToOpenState();
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.circuitbreakers.components.usuarios.status").value("OPEN"));
        circuitos.circuitBreaker("usuarios").transitionToHalfOpenState();
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.circuitbreakers.components.usuarios.status").value("HALF_OPEN"));

        healthRegistry.registerContributor("pruebaDown",
                (org.springframework.boot.actuate.health.HealthIndicator)
                        () -> org.springframework.boot.actuate.health.Health.down().build());
        try {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"));
        } finally {
            healthRegistry.unregisterContributor("pruebaDown");
        }
    }

    @Test
    void detalleHttpCombinaDisponibilidadPorSectorIdYDejaNullLosSectoresFaltantes() throws Exception {
        Evento evento = guardarEventoPublicado();
        when(reservasClient.consultarDisponibilidad(evento.getId())).thenReturn(new DisponibilidadEventoResponse(
                evento.getId(), List.of(
                        new SectorDisponibilidadResponse("sector-b", TipoSector.NO_NUMERADO, 50, 17, null),
                        new SectorDisponibilidadResponse("sector-a", TipoSector.NUMERADO, 2, 1,
                                List.of(new AsientoDisponibleResponse(1, 2))))));

        mockMvc.perform(get("/eventos/" + evento.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectores[0].sectorId").value("sector-a"))
                .andExpect(jsonPath("$.sectores[0].precio").value(12000))
                .andExpect(jsonPath("$.sectores[0].disponibilidad.capacidadTotal").value(2))
                .andExpect(jsonPath("$.sectores[0].disponibilidad.cantidadDisponible").value(1))
                .andExpect(jsonPath("$.sectores[0].disponibilidad.asientosDisponibles[0].fila").value(1))
                .andExpect(jsonPath("$.sectores[0].disponibilidad.asientosDisponibles[0].numero").value(2))
                .andExpect(jsonPath("$.sectores[1].disponibilidad.cantidadDisponible").value(17))
                .andExpect(jsonPath("$.sectores[2].disponibilidad").value(org.hamcrest.Matchers.nullValue()));
        verify(reservasClient).consultarDisponibilidad(evento.getId());
    }

    @Test
    void detalleSigueRespondiendo200SiReservasFalla() throws Exception {
        Evento evento = guardarEventoPublicado();
        when(reservasClient.consultarDisponibilidad(evento.getId()))
                .thenThrow(new ReservasClientException("Reservas no responde"));

        mockMvc.perform(get("/eventos/" + evento.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Festival de prueba"))
                .andExpect(jsonPath("$.sectores[0].disponibilidad").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.sectores[1].disponibilidad").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void borradoresRequierenAutorizacionYEstadosNoVendiblesNoConsultanReservas() throws Exception {
        Lugar lugar = crearLugar();
        Evento borrador = crearEvento(lugar, EstadoEvento.BORRADOR);
        mockMvc.perform(get("/eventos/" + borrador.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/eventos/" + borrador.getId())
                        .header("X-User-Id", "org-1").header("X-User-Roles", "CLIENTE, ORGANIZADOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectores[0].disponibilidad").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/eventos/" + borrador.getId())
                        .header("X-User-Id", "org-2").header("X-User-Roles", "ORGANIZADOR"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/eventos/" + borrador.getId())
                        .header("X-User-Id", "org-1").header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/eventos/" + borrador.getId())
                        .header("X-User-Id", "admin-1").header("X-User-Roles", "CLIENTE, ADMIN"))
                .andExpect(status().isOk());

        Evento cancelado = crearEvento(lugar, EstadoEvento.CANCELADO);
        mockMvc.perform(get("/eventos/" + cancelado.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("cancelado"))
                .andExpect(jsonPath("$.sectores[0].disponibilidad").value(org.hamcrest.Matchers.nullValue()));

        Evento finalizado = crearEvento(lugar, EstadoEvento.FINALIZADO);
        mockMvc.perform(get("/eventos/" + finalizado.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("finalizado"))
                .andExpect(jsonPath("$.sectores[0].disponibilidad").value(org.hamcrest.Matchers.nullValue()));
        verifyNoInteractions(reservasClient);
    }

    @Test
    void detallePublicoIgnoraRolesDesconocidosComoSolicitudAnonima() throws Exception {
        Evento evento = guardarEventoPublicado();

        mockMvc.perform(get("/eventos/" + evento.getId())
                        .header("X-User-Id", "user-1")
                        .header("X-User-Roles", "VISITANTE_DESCONOCIDO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("publicado"));
    }

    @Test
    void modificaFechaYLugarYLasConsultasReflejanDatosSinCambiarSectores() throws Exception {
        Evento evento = guardarEventoPublicado();
        Lugar nuevo = lugarRepository.save(new Lugar(null, "Nuevo teatro", "Otra calle", "Rosario",
                -32.0, -60.0, List.of()));
        Instant fecha = Instant.now().plusSeconds(172800).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        when(reservasClient.consultarDisponibilidad(evento.getId())).thenReturn(new DisponibilidadEventoResponse(
                evento.getId(), List.of(new SectorDisponibilidadResponse("sector-a", TipoSector.NUMERADO, 2, 1, null))));

        mockMvc.perform(put("/eventos/" + evento.getId())
                        .header("X-User-Id", "org-1").header("X-User-Roles", "CLIENTE, ORGANIZADOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fechaHora\":\"" + fecha + "\",\"lugarId\":\"" + nuevo.getId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechaHora").value(fecha.toString()))
                .andExpect(jsonPath("$.lugarId").value(nuevo.getId()))
                .andExpect(jsonPath("$.ciudad").value("Rosario"))
                .andExpect(jsonPath("$.sectores[0].sectorId").value("sector-a"));

        Evento actualizado = eventoRepository.findById(evento.getId()).orElseThrow();
        assertEquals(evento.getVersion() + 1, actualizado.getVersion());
        assertEquals(evento.getSectores().getFirst().getPrecio(), actualizado.getSectores().getFirst().getPrecio());
        mockMvc.perform(get("/eventos/" + evento.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechaHora").value(fecha.toString()))
                .andExpect(jsonPath("$.lugar.nombre").value("Nuevo teatro"))
                .andExpect(jsonPath("$.sectores[0].disponibilidad.cantidadDisponible").value(1));
        mockMvc.perform(get("/internal/eventos/" + evento.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechaHora").value(fecha.toString()))
                .andExpect(jsonPath("$.ciudad").value("Rosario"));

        mockMvc.perform(put("/eventos/" + evento.getId())
                        .header("X-User-Id", "admin-1").header("X-User-Roles", "CLIENTE, ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fechaHora\":\"" + fecha + "\",\"lugarId\":\"" + nuevo.getId() + "\"}"))
                .andExpect(status().isOk());
        assertEquals(actualizado.getVersion(), eventoRepository.findById(evento.getId()).orElseThrow().getVersion());
    }

    @Test
    void modificaCamposIndividualesEnBorradorYAdminNoTitular() throws Exception {
        Evento evento = crearEvento(crearLugar(), EstadoEvento.BORRADOR);
        Instant fecha = Instant.now().plusSeconds(172800).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Lugar nuevo = lugarRepository.save(new Lugar(null, "Nuevo", "Calle", "Rosario", 0, 0, List.of()));

        mockMvc.perform(put("/eventos/" + evento.getId())
                        .header("X-User-Id", "admin-1").header("X-User-Roles", "CLIENTE, ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fechaHora\":\"" + fecha + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lugarId").value(evento.getLugarId()));
        mockMvc.perform(put("/eventos/" + evento.getId())
                        .header("X-User-Id", "org-1").header("X-User-Roles", "ORGANIZADOR")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lugarId\":\"" + nuevo.getId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechaHora").value(fecha.toString()))
                .andExpect(jsonPath("$.ciudad").value("Rosario"));
        assertEquals(evento.getVersion() + 2, eventoRepository.findById(evento.getId()).orElseThrow().getVersion());
    }

    @Test
    void rechazaIdentidadPermisosEstadosYDatosInvalidos() throws Exception {
        Evento evento = guardarEventoPublicado();
        String path = "/eventos/" + evento.getId();
        String fechaValida = "{\"fechaHora\":\"2030-01-01T00:00:00Z\"}";
        mockMvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(fechaValida))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(path).header("X-User-Id", "org-2").header("X-User-Roles", "ORGANIZADOR")
                        .contentType(MediaType.APPLICATION_JSON).content(fechaValida))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(path).header("X-User-Id", "org-1").header("X-User-Roles", "CLIENTE")
                        .contentType(MediaType.APPLICATION_JSON).content(fechaValida))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/eventos/inexistente").header("X-User-Id", "admin")
                        .header("X-User-Roles", "ADMIN").contentType(MediaType.APPLICATION_JSON).content(fechaValida))
                .andExpect(status().isNotFound());

        for (String body : List.of("", "{}", "{\"fechaHora\":\"2020-01-01T00:00:00Z\"}",
                "{\"fechaHora\":\"invalida\"}", "{\"fechaHora\":\"2030-01-01T00:00:00+02:00\"}",
                "{\"lugarId\":\"inexistente\"}")) {
            mockMvc.perform(put(path).header("X-User-Id", "org-1").header("X-User-Roles", "ORGANIZADOR")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        for (EstadoEvento estado : List.of(EstadoEvento.CANCELADO, EstadoEvento.FINALIZADO)) {
            evento.setEstado(estado);
            evento = eventoRepository.save(evento);
            mockMvc.perform(put(path).header("X-User-Id", "org-1").header("X-User-Roles", "ORGANIZADOR")
                            .contentType(MediaType.APPLICATION_JSON).content(fechaValida))
                    .andExpect(status().isConflict());
        }
        evento.setEstado(EstadoEvento.PUBLICADO);
        evento.setCancelacionPendiente(true);
        eventoRepository.save(evento);
        mockMvc.perform(put(path).header("X-User-Id", "org-1").header("X-User-Roles", "ORGANIZADOR")
                        .contentType(MediaType.APPLICATION_JSON).content(fechaValida))
                .andExpect(status().isConflict());
    }

    @Test
    void versionImpideSobrescribirIntencionDeCancelacionConcurrente() {
        Evento evento = guardarEventoPublicado();
        Evento obsoleto = eventoRepository.findById(evento.getId()).orElseThrow();
        Evento intencion = eventoRepository.findById(evento.getId()).orElseThrow();
        intencion.setCancelacionPendiente(true);
        eventoRepository.save(intencion);
        obsoleto.setFechaHora(Instant.now().plusSeconds(172800));

        assertThrows(OptimisticLockingFailureException.class, () -> eventoRepository.save(obsoleto));
        Evento persistido = eventoRepository.findById(evento.getId()).orElseThrow();
        assertEquals(true, persistido.isCancelacionPendiente());
        assertEquals(evento.getFechaHora(), persistido.getFechaHora());
    }

    @Test
    void avisosCompletosUsanIdPublicoYVersionCrecienteEnCambiosSucesivos() throws Exception {
        Lugar original = crearLugar();
        Evento evento = crearEvento(original, EstadoEvento.AGOTADO);
        Lugar nuevo = lugarRepository.save(new Lugar(null, "Nuevo teatro", "Otra calle", "Rosario",
                -32, -60, List.of()));
        Instant primeraFecha = Instant.parse("2035-02-01T12:00:00Z");
        Instant segundaFecha = Instant.parse("2035-03-01T12:00:00Z");
        String idPublico = objectMapper.readTree(mockMvc.perform(get("/eventos/{id}", evento.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();
        long versionInicial = evento.getVersion();

        modificar(evento.getId(), "{\"fechaHora\":\"" + primeraFecha + "\"}");
        AvisoOutbox primero = outboxRepository.findById(
                "modificacion-" + idPublico + "-v" + (versionInicial + 1)).orElseThrow();
        verificarAviso(primero, idPublico, versionInicial + 1, primeraFecha, "Estadio", "CABA");

        modificar(evento.getId(), "{\"lugarId\":\"" + nuevo.getId() + "\"}");
        AvisoOutbox segundo = outboxRepository.findById(
                "modificacion-" + idPublico + "-v" + (versionInicial + 2)).orElseThrow();
        verificarAviso(segundo, idPublico, versionInicial + 2, primeraFecha, "Nuevo teatro", "Rosario");

        modificar(evento.getId(), "{\"fechaHora\":\"" + segundaFecha
                + "\",\"lugarId\":\"" + original.getId() + "\"}");
        AvisoOutbox tercero = outboxRepository.findById(
                "modificacion-" + idPublico + "-v" + (versionInicial + 3)).orElseThrow();
        verificarAviso(tercero, idPublico, versionInicial + 3, segundaFecha, "Estadio", "CABA");
        assertEquals(versionInicial + 3, eventoRepository.findById(evento.getId()).orElseThrow().getVersion());
        assertEquals(3, outboxRepository.count());
    }

    @Test
    void borradorYCambioIdenticoNoCreanAviso() throws Exception {
        Lugar lugar = crearLugar();
        Evento borrador = crearEvento(lugar, EstadoEvento.BORRADOR);
        modificar(borrador.getId(), "{\"fechaHora\":\"2035-01-01T12:00:00Z\"}");
        assertEquals(0, outboxRepository.count());

        Evento publicado = crearEvento(lugar, EstadoEvento.PUBLICADO);
        modificar(publicado.getId(), "{\"fechaHora\":\"" + publicado.getFechaHora()
                + "\",\"lugarId\":\"" + lugar.getId() + "\"}");
        assertEquals(publicado.getVersion(), eventoRepository.findById(publicado.getId()).orElseThrow().getVersion());
        assertEquals(0, outboxRepository.count());
    }

    private void modificar(String eventoId, String body) throws Exception {
        mockMvc.perform(put("/eventos/{id}", eventoId)
                        .header("X-User-Id", "org-1").header("X-User-Roles", "ORGANIZADOR")
                        .header("X-Correlation-Id", "modificacion-traza")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private void verificarAviso(AvisoOutbox aviso, String idPublico, long version, Instant fecha,
            String lugar, String ciudad) {
        assertEquals(idPublico, aviso.getEventoId());
        assertEquals("MODIFICACION", aviso.getTipo());
        assertNull(aviso.getMetodoHttp());
        assertTrue(aviso.getDestino().endsWith("/internal/eventos-modificados"));
        assertEquals("modificacion-traza", aviso.getCorrelationId());
        assertEquals(Set.of("eventoId", "version", "fechaHora", "lugar", "ciudad"), aviso.getPayload().keySet());
        assertEquals(idPublico, aviso.getPayload().get("eventoId"));
        assertTrue(aviso.getPayload().get("version") instanceof Integer
                || aviso.getPayload().get("version") instanceof Long);
        assertEquals(version, ((Number) aviso.getPayload().get("version")).longValue());
        assertEquals(fecha.toString(), aviso.getPayload().get("fechaHora"));
        assertEquals(lugar, aviso.getPayload().get("lugar"));
        assertEquals(ciudad, aviso.getPayload().get("ciudad"));
    }

    private Evento guardarEventoPublicado() {
        Lugar lugar = crearLugar();
        Evento evento = crearEvento(lugar, EstadoEvento.PUBLICADO);
        return eventoRepository.save(evento);
    }

    private Lugar crearLugar() {
        return lugarRepository.save(new Lugar(null, "Estadio", "Av. Central", "CABA", -34.0, -58.0, List.of()));
    }

    private Evento crearEvento(Lugar lugar, EstadoEvento estado) {
        Evento evento = Evento.borrador("org-1", lugar.getId(), lugar.getCiudad(), "Festival de prueba",
                "Descripción", "MUSICA", List.of("Artista"), Instant.parse("2027-05-16T00:00:00Z"), 4,
                List.of(new EventoSector("sector-a", "Platea", TipoSector.NUMERADO, 1, 2, null,
                                new java.math.BigDecimal("12000")),
                        new EventoSector("sector-b", "Campo", TipoSector.NO_NUMERADO, null, null, 50,
                                new java.math.BigDecimal("8000")),
                        new EventoSector("sector-c", "Balcón", TipoSector.NUMERADO, 1, 1, null,
                                new java.math.BigDecimal("6000"))));
        evento.setEstado(estado);
        return eventoRepository.save(evento);
    }
}
