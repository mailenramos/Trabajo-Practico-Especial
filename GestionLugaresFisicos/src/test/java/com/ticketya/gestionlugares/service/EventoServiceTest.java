package com.ticketya.gestionlugares.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import com.ticketya.gestionlugares.api.CreateEventoRequest;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.Sector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.LugarRepository;

@ExtendWith(MockitoExtension.class)
class EventoServiceTest {

    private static final String LUGAR_ID = "507f1f77bcf86cd799439011";
    private static final String EVENTO_ID = "672a1f77bcf86cd799439011";
    private static final String ORGANIZADOR = "organizador-1";

    @Mock private EventoRepository eventoRepository;
    @Mock private LugarRepository lugarRepository;

    private EventoService service;

    @BeforeEach
    void setUp() {
        service = new EventoService(eventoRepository, lugarRepository);
        lenient().when(eventoRepository.save(any(Evento.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void creaBorradorCopiandoCiudadYSectoresYUsaLimitePorDefecto() {
        Lugar lugar = lugar();
        when(lugarRepository.findById(LUGAR_ID)).thenReturn(Optional.of(lugar));

        Evento creado = service.crearEvento(request(null), ORGANIZADOR, "organizador");

        assertEquals(24, creado.getId().length());
        assertEquals(EstadoEvento.BORRADOR, creado.getEstado());
        assertEquals("Buenos Aires", creado.getCiudad());
        assertEquals(4, creado.getLimiteEntradasPorCliente());
        assertEquals("sector-uuid-1", creado.getSectores().getFirst().getSectorId());
        assertEquals("Platea", creado.getSectores().getFirst().getNombre());
        assertEquals(null, creado.getSectores().getFirst().getPrecio());
    }

    @Test
    void creaConLimiteDefinidoDuranteBorrador() {
        when(lugarRepository.findById(LUGAR_ID)).thenReturn(Optional.of(lugar()));
        Evento creado = service.crearEvento(request(8), ORGANIZADOR, "organizador");
        assertEquals(8, creado.getLimiteEntradasPorCliente());
    }

    @Test
    void rechazaLugarInexistenteFechaPasadaYRolNoOrganizador() {
        when(lugarRepository.findById(LUGAR_ID)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.crearEvento(request(null), ORGANIZADOR, "organizador"));

        CreateEventoRequest pasada = new CreateEventoRequest("Festival", "Descripción", "MUSICA",
                List.of("Artista"), Instant.parse("2020-01-01T00:00:00Z"), LUGAR_ID, null);
        assertThrows(IllegalArgumentException.class, () -> service.crearEvento(pasada, ORGANIZADOR, "organizador"));
        assertThrows(ForbiddenException.class, () -> service.crearEvento(request(null), "admin-1", "administrador"));
        assertThrows(UnauthorizedException.class, () -> service.crearEvento(request(null), " ", "organizador"));
    }

    @Test
    void asignaPrecioDecimalSoloAlSectorDelLugarYAlDueno() {
        Evento evento = eventoBorrador();
        when(eventoRepository.findById(EVENTO_ID)).thenReturn(Optional.of(evento));
        when(lugarRepository.findById(LUGAR_ID)).thenReturn(Optional.of(lugar()));

        Evento guardado = service.asignarPrecio(EVENTO_ID, "sector-uuid-1", new BigDecimal("15000.25"),
                ORGANIZADOR, "organizador");

        assertEquals(new BigDecimal("15000.25"), guardado.getSectores().getFirst().getPrecio());
        assertThrows(ForbiddenException.class, () -> service.asignarPrecio(EVENTO_ID, "sector-uuid-1",
                BigDecimal.ONE, "otro", "organizador"));
        ResourceNotFoundException sectorAjeno = assertThrows(ResourceNotFoundException.class,
                () -> service.asignarPrecio(EVENTO_ID, "sector-ajeno", BigDecimal.ONE,
                        ORGANIZADOR, "organizador"));
        assertEquals("El sector sector-ajeno no pertenece al lugar del evento", sectorAjeno.getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.asignarPrecio(EVENTO_ID, "sector-uuid-1",
                new BigDecimal("-0.01"), ORGANIZADOR, "organizador"));
    }

    @Test
    void fijaLimiteEnBorradorYRechazaLimiteInvalidoOPublicado() {
        Evento evento = eventoBorrador();
        when(eventoRepository.findById(EVENTO_ID)).thenReturn(Optional.of(evento));
        assertEquals(7, service.actualizarLimiteEntradas(EVENTO_ID, 7, ORGANIZADOR, "organizador")
                .getLimiteEntradasPorCliente());
        assertThrows(IllegalArgumentException.class,
                () -> service.actualizarLimiteEntradas(EVENTO_ID, 0, ORGANIZADOR, "organizador"));

        evento.setEstado(EstadoEvento.PUBLICADO);
        assertThrows(ConflictException.class,
                () -> service.actualizarLimiteEntradas(EVENTO_ID, 3, ORGANIZADOR, "organizador"));
    }

    @Test
    void publicaCalculandoMinimoDecimalYPreciosLuegoQuedanInmutables() {
        Evento evento = eventoBorrador();
        evento.fijarPrecio("sector-uuid-1", new BigDecimal("15000.25"));
        evento.getSectores().add(new EventoSector("sector-uuid-2", "Campo", TipoSector.NO_NUMERADO,
                null, null, 100, new BigDecimal("1200.10")));
        when(eventoRepository.findById(EVENTO_ID)).thenReturn(Optional.of(evento));

        Evento publicado = service.confirmarPublicacion(EVENTO_ID, ORGANIZADOR, "organizador");

        assertEquals(EstadoEvento.PUBLICADO, publicado.getEstado());
        assertEquals(new BigDecimal("1200.10"), publicado.getPrecioMinimo());
        assertEquals(new BigDecimal("15000.25"), publicado.getSectores().getFirst().getPrecio());
        assertThrows(ConflictException.class, () -> service.asignarPrecio(EVENTO_ID,
                "sector-uuid-1", BigDecimal.ONE, ORGANIZADOR, "organizador"));
    }

    @Test
    void requierePrecioEnCadaSectorParaPublicar() {
        Evento evento = eventoBorrador();
        when(eventoRepository.findById(EVENTO_ID)).thenReturn(Optional.of(evento));
        assertThrows(IllegalArgumentException.class,
                () -> service.confirmarPublicacion(EVENTO_ID, ORGANIZADOR, "organizador"));
        assertEquals(EstadoEvento.BORRADOR, evento.getEstado());
    }

    @Test
    void suspensionEsIndependienteYTransicionesValidaEstados() {
        Evento evento = eventoBorrador();
        evento.fijarPrecio("sector-uuid-1", BigDecimal.ZERO);
        evento.definirPrecioMinimoYPublicar();
        when(eventoRepository.findById(EVENTO_ID)).thenReturn(Optional.of(evento));

        Evento suspendido = service.suspenderVentaLocal(EVENTO_ID);
        assertTrue(suspendido.isVentaSuspendida());
        assertEquals(EstadoEvento.PUBLICADO, suspendido.getEstado());
        assertEquals(EstadoEvento.PUBLICADO, service.reactivarVentaLocal(EVENTO_ID).getEstado());
        assertEquals(EstadoEvento.CANCELADO, service.cancelarLocal(EVENTO_ID).getEstado());
        assertThrows(IllegalStateException.class, () -> evento.cancelar());
    }

    @Test
    void agotadoYRecuperacionSoloCambianEntrePublicadoYAgotado() {
        Evento evento = eventoBorrador();
        evento.setEstado(EstadoEvento.PUBLICADO);
        when(eventoRepository.findById(EVENTO_ID)).thenReturn(Optional.of(evento));
        assertEquals(EstadoEvento.AGOTADO, service.aplicarDisponibilidadLocal(EVENTO_ID, true).getEstado());
        assertEquals(EstadoEvento.PUBLICADO, service.aplicarDisponibilidadLocal(EVENTO_ID, false).getEstado());
        evento.setEstado(EstadoEvento.BORRADOR);
        assertThrows(IllegalStateException.class, () -> service.aplicarDisponibilidadLocal(EVENTO_ID, true));
    }

    @Test
    void convierteConflictoDeVersionMongoEnConflictoDeDominio() {
        Evento evento = eventoBorrador();
        evento.fijarPrecio("sector-uuid-1", BigDecimal.ONE);
        when(eventoRepository.findById(EVENTO_ID)).thenReturn(Optional.of(evento));
        when(lugarRepository.findById(LUGAR_ID)).thenReturn(Optional.of(lugar()));
        when(eventoRepository.save(any(Evento.class)))
                .thenThrow(new OptimisticLockingFailureException("version desactualizada"));

        assertThrows(ConflictException.class, () -> service.asignarPrecio(EVENTO_ID,
                "sector-uuid-1", BigDecimal.TEN, ORGANIZADOR, "organizador"));
    }

    @Test
    void serializaIdsEstadoFechaYPrecioComoContratoJson() throws Exception {
        Evento evento = eventoBorrador();
        evento.getSectores().getFirst().setPrecio(new BigDecimal("15.20"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonNode json = mapper.readTree(mapper.writeValueAsString(evento));

        assertTrue(json.path("id").asText().matches("[0-9a-f]{24}"));
        assertEquals("borrador", json.path("estado").asText());
        assertTrue(json.path("fechaHora").isTextual());
        assertEquals(0, json.path("sectores").get(0).path("precio").decimalValue()
                .compareTo(new BigDecimal("15.20")));
    }

    private static CreateEventoRequest request(Integer limite) {
        return new CreateEventoRequest("Festival", "Descripción", "MUSICA", List.of("Artista"),
                Instant.now().plusSeconds(86400), LUGAR_ID, limite);
    }

    private static Lugar lugar() {
        return new Lugar(LUGAR_ID, "Teatro", "Calle 1", "Buenos Aires", -34.0, -58.0,
                List.of(new Sector("sector-uuid-1", "Platea", TipoSector.NUMERADO, 2, 5, null)));
    }

    private static Evento eventoBorrador() {
        return Evento.borrador(ORGANIZADOR, LUGAR_ID, "Buenos Aires", "Festival", "Descripción",
                "MUSICA", List.of("Artista"), Instant.now().plusSeconds(86400), 4,
                List.of(new EventoSector("sector-uuid-1", "Platea", TipoSector.NUMERADO,
                        2, 5, null, null)));
    }
}
