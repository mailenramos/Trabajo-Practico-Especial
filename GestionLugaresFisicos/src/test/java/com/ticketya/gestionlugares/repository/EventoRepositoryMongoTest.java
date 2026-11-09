package com.ticketya.gestionlugares.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.mongodb.client.MongoClient;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.api.EventoDetalleResponse;
import com.ticketya.gestionlugares.config.MongoDecimalConfiguration;
import com.ticketya.gestionlugares.service.EventoConsultaService;
import com.ticketya.gestionlugares.reservas.ReservasClient;
import com.ticketya.gestionlugares.reservas.ReservasClientException;
import com.ticketya.gestionlugares.reservas.dto.DisponibilidadEventoResponse;
import com.ticketya.gestionlugares.reservas.dto.SectorDisponibilidadResponse;
import com.ticketya.gestionlugares.reservas.dto.AsientoDisponibleResponse;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.anyString;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataMongoTest
@Import({MongoDecimalConfiguration.class, EventoConsultaService.class})
@Testcontainers(disabledWithoutDocker = true)
class EventoRepositoryMongoTest {

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl()
                .replace("/test", "/ticketya_lugares"));
    }

    @Autowired private EventoRepository eventoRepository;
    @Autowired private LugarRepository lugarRepository;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private EventoConsultaService consultaService;
    @MockBean private ReservasClient reservasClient;
    @Autowired private MongoClient mongoClient;

    @BeforeEach
    void limpiarColeccion() {
        eventoRepository.deleteAll();
        lugarRepository.deleteAll();
    }

    @Test
    void persisteEventoConObjectIdSectoresEmbebidosYPrecioDecimal128() {
        Evento evento = Evento.borrador("org-1", "507f1f77bcf86cd799439011", "Buenos Aires",
                "Festival", "Descripción", "MUSICA", List.of("Artista"),
                Instant.parse("2027-01-01T00:00:00Z"), 4,
                List.of(new EventoSector("sector-1", "Platea", TipoSector.NUMERADO,
                        1, 2, null, new BigDecimal("15000.25"))));

        eventoRepository.save(evento);

        Evento recuperado = eventoRepository.findById(evento.getId()).orElseThrow();
        assertEquals(EstadoEvento.BORRADOR, recuperado.getEstado());
        assertEquals("Buenos Aires", recuperado.getCiudad());
        assertEquals(4, recuperado.getLimiteEntradasPorCliente());
        assertEquals("Platea", recuperado.getSectores().getFirst().getNombre());
        assertEquals(new BigDecimal("15000.25"), recuperado.getSectores().getFirst().getPrecio());
        ObjectId idPersistido = (ObjectId) mongoClient.getDatabase("ticketya_lugares")
                .getCollection("eventos").find().first().get("_id");
        assertEquals(evento.getId(), idPersistido.toHexString());
        assertInstanceOf(Decimal128.class, mongoClient.getDatabase("ticketya_lugares")
                .getCollection("eventos").find().first().getList("sectores", org.bson.Document.class)
                .getFirst().get("precio"));
        assertInstanceOf(List.class, mongoClient.getDatabase("ticketya_lugares")
                .getCollection("eventos").find().first().get("sectores"));
        assertNull(recuperado.getPrecioMinimo());
    }

    @Test
    void consultaPorOrganizadorYEstado() {
        eventoRepository.save(Evento.borrador("org-1", "lugar-1", "CABA", "Uno", "Desc", "MUSICA",
                List.of("A"), Instant.parse("2027-01-01T00:00:00Z"), 4, List.of()));
        assertEquals(1, eventoRepository.findByOrganizadorIdAndEstado("org-1", EstadoEvento.BORRADOR).size());
    }

    @Test
    void persisteAvisoDeCancelacionPendienteYPermiteBuscarloParaReenvio() {
        Evento evento = Evento.borrador("org-1", "lugar-1", "CABA", "Show", "Desc", "MUSICA",
                List.of("A"), Instant.parse("2027-01-01T00:00:00Z"), 4, List.of());
        evento.setEstado(EstadoEvento.PUBLICADO);
        evento.cancelar("Cierre preventivo", Instant.parse("2026-11-06T18:00:00Z"));
        eventoRepository.save(evento);

        Evento recuperado = eventoRepository.findByAvisoCancelacionPendienteTrue().getFirst();

        assertEquals(EstadoEvento.CANCELADO, recuperado.getEstado());
        assertEquals("Cierre preventivo", recuperado.getMotivoCancelacion());
        assertEquals(Instant.parse("2026-11-06T18:00:00Z"), recuperado.getCanceladoEn());
        assertEquals(true, recuperado.isAvisoCancelacionPendiente());
    }

    @Test
    void creaIndicesParaFiltrosYTexto() {
        List<String> nombres = mongoTemplate.indexOps(Evento.class).getIndexInfo().stream()
                .map(IndexInfo::getName).toList();
        assertTrue(nombres.contains("idx_eventos_estado_fecha"), nombres.toString());
        assertTrue(nombres.contains("idx_eventos_estado_ciudad_fecha"), nombres.toString());
        assertTrue(nombres.contains("idx_eventos_organizador_estado"), nombres.toString());
        assertTrue(nombres.contains("organizadorId"), nombres.toString());
        assertTrue(nombres.contains("idx_eventos_tipo"), nombres.toString());
        assertTrue(nombres.contains("idx_eventos_participantes"), nombres.toString());
        assertTrue(nombres.contains("Evento_TextIndex"), nombres.toString());
    }

    @Test
    void aplicaFiltrosAndEstadosVisiblesPaginacionYOrdenEnMongo() {
        guardarEvento("org-1", "CABA", "Festival Uno", "Rock nacional", "MUSICA", "Artista A",
                "2027-05-16T00:00:00Z", EstadoEvento.PUBLICADO, "20000");
        guardarEvento("org-2", "CABA", "Festival Dos", "Pop", "MUSICA", "Artista B",
                "2027-06-16T00:00:00Z", EstadoEvento.AGOTADO, "10000");
        guardarEvento("org-1", "CABA", "Borrador", "Rock", "MUSICA", "Artista A",
                "2027-04-16T00:00:00Z", EstadoEvento.BORRADOR, null);
        guardarEvento("org-1", "CABA", "Cancelado", "Desc", "MUSICA", "Artista C",
                "2027-07-16T00:00:00Z", EstadoEvento.CANCELADO, "5000");
        guardarEvento("org-1", "CABA", "Finalizado", "Desc", "MUSICA", "Artista D",
                "2027-03-16T00:00:00Z", EstadoEvento.FINALIZADO, "5000");

        var respuesta = consultaService.buscar("MUSICA", "org-1", "Artista A", "CABA",
                "2027-05-16T00:00:00Z", "2027-05-16T00:00:00Z", "fecha", "desc",
                null, "0", "20", "cliente-1", "CLIENTE");

        assertEquals(1, respuesta.totalElementos());
        assertEquals(1, respuesta.contenido().size());
        assertEquals("Festival Uno", respuesta.contenido().getFirst().nombre());
        var vacia = consultaService.buscar("MUSICA", "no-existe", null, null, null, null,
                null, null, null, null, null, "cliente-1", "CLIENTE");
        assertEquals(0, vacia.totalElementos());
        assertEquals(0, vacia.totalPaginas());
        var paginada = consultaService.buscar(null, null, null, null, null, null,
                null, null, null, "0", "1", "cliente-1", "CLIENTE");
        assertEquals(2, paginada.totalElementos());
        assertEquals(2, paginada.totalPaginas());
        assertEquals("Festival Uno", paginada.contenido().getFirst().nombre());
        for (String rol : List.of("CLIENTE", "ORGANIZADOR", "ADMIN")) {
            assertEquals(2, consultaService.buscar(null, null, null, null, null, null,
                    null, null, null, null, null, "user-1", rol).totalElementos());
        }
    }

    @Test
    void ordenaPorPrecioYRelevanciaTextual() {
        guardarEvento("org-1", "CABA", "Rock Rock Rock", "Rock en vivo", "MUSICA", "Banda A",
                "2027-06-16T00:00:00Z", EstadoEvento.PUBLICADO, "20000");
        guardarEvento("org-1", "CABA", "Rock Central", "Festival en vivo", "MUSICA", "Banda B",
                "2027-05-16T00:00:00Z", EstadoEvento.PUBLICADO, "10000");

        var asc = consultaService.buscar(null, null, null, null, null, null,
                "precio", "asc", null, null, null, "cliente-1", "CLIENTE");
        assertEquals("Rock Central", asc.contenido().getFirst().nombre());
        var desc = consultaService.buscar(null, null, null, null, null, null,
                "precio", "desc", null, null, null, "cliente-1", "CLIENTE");
        assertEquals("Rock Rock Rock", desc.contenido().getFirst().nombre());
        var relevance = consultaService.buscar(null, null, null, null, null, null,
                "relevancia", "asc", "Rock", null, null, "cliente-1", "CLIENTE");
        assertEquals(2, relevance.totalElementos());
        assertEquals("Rock Rock Rock", relevance.contenido().getFirst().nombre());
        var relevanceSinTexto = consultaService.buscar(null, null, null, null, null, null,
                "relevancia", "desc", null, null, null, "cliente-1", "CLIENTE");
        assertEquals("Rock Central", relevanceSinTexto.contenido().getFirst().nombre());
    }

    @Test
    void devuelveDetalleLocalYOcultaBorradorDeOtroOrganizador() {
        Lugar lugar = lugarRepository.save(new Lugar(null, "Estadio", "Av. Central", "CABA", -34.0, -58.0, List.of()));
        Evento borrador = Evento.borrador("org-1", lugar.getId(), lugar.getCiudad(), "Borrador", "Desc",
                "MUSICA", List.of("Artista"), Instant.parse("2027-05-16T00:00:00Z"), 4,
                List.of(new EventoSector("sector-1", "Platea", TipoSector.NUMERADO, 1, 2, null, null)));
        eventoRepository.save(borrador);

        var propio = consultaService.detalle(borrador.getId(), "org-1", "ORGANIZADOR");
        assertEquals("CABA", propio.lugar().ciudad());
        assertEquals(null, propio.sectores().getFirst().disponibilidad());
        verifyNoInteractions(reservasClient);
        org.junit.jupiter.api.Assertions.assertThrows(com.ticketya.gestionlugares.service.ResourceNotFoundException.class,
                () -> consultaService.detalle(borrador.getId(), "org-2", "CLIENTE"));
        assertEquals("borrador", consultaService.detalle(borrador.getId(), "admin-1", "ADMIN").estado());
    }

    @Test
    void detalleVisibleParaLosTresRolesEnTodosLosEstadosNoBorrador() {
        Lugar lugar = lugarRepository.save(new Lugar(null, "Estadio", "Av. Central", "CABA", -34.0, -58.0, List.of()));
        for (EstadoEvento estado : List.of(EstadoEvento.PUBLICADO, EstadoEvento.AGOTADO,
                EstadoEvento.CANCELADO, EstadoEvento.FINALIZADO)) {
            Evento evento = Evento.borrador("org-1", lugar.getId(), lugar.getCiudad(), estado.name(), "Desc",
                    "MUSICA", List.of("Artista"), Instant.parse("2027-05-16T00:00:00Z"), 4, List.of());
            evento.setEstado(estado);
            eventoRepository.save(evento);
            for (String[] identidad : List.of(new String[]{"c1", "CLIENTE"},
                    new String[]{"o1", "ORGANIZADOR"}, new String[]{"a1", "ADMIN"})) {
                assertEquals(estado.getValor(), consultaService.detalle(evento.getId(), identidad[0], identidad[1]).estado());
            }
        }
        verify(reservasClient, times(6)).consultarDisponibilidad(anyString());
    }

    @Test
    void combinaDisponibilidadPorSectorIdAunqueReservasDevuelvaOtroOrden() {
        Lugar lugar = lugarRepository.save(new Lugar(null, "Estadio", "Av. Central", "CABA", -34.0, -58.0, List.of()));
        Evento evento = Evento.borrador("org-1", lugar.getId(), lugar.getCiudad(), "Festival", "Desc",
                "MUSICA", List.of("Artista"), Instant.parse("2027-05-16T00:00:00Z"), 4,
                List.of(new EventoSector("sector-a", "Platea", TipoSector.NUMERADO, 1, 2, null,
                                new BigDecimal("10000")),
                        new EventoSector("sector-b", "Campo", TipoSector.NO_NUMERADO, null, null, 50,
                                new BigDecimal("5000"))));
        evento.setEstado(EstadoEvento.PUBLICADO);
        eventoRepository.save(evento);
        when(reservasClient.consultarDisponibilidad(evento.getId())).thenReturn(new DisponibilidadEventoResponse(
                evento.getId(), List.of(
                        new SectorDisponibilidadResponse("sector-b", TipoSector.NO_NUMERADO, 50, 17, null),
                        new SectorDisponibilidadResponse("sector-desconocido", TipoSector.NUMERADO, 1, 1, List.of()),
                        new SectorDisponibilidadResponse("sector-a", TipoSector.NUMERADO, 2, 1,
                                List.of(new AsientoDisponibleResponse(1, 2))))));

        var detalle = consultaService.detalle(evento.getId(), "cliente-1", "CLIENTE");

        assertEquals("sector-a", detalle.sectores().get(0).sectorId());
        assertEquals(1, detalle.sectores().get(0).disponibilidad().cantidadDisponible());
        assertEquals(new EventoDetalleResponse.AsientoResponse(1, 2),
                detalle.sectores().get(0).disponibilidad().asientosDisponibles().getFirst());
        assertEquals(new BigDecimal("10000"), detalle.sectores().get(0).precio());
        assertEquals("sector-b", detalle.sectores().get(1).sectorId());
        assertEquals(17, detalle.sectores().get(1).disponibilidad().cantidadDisponible());
    }

    @Test
    void anteFallaDeReservasDevuelveDetalleConDisponibilidadNula() {
        Lugar lugar = lugarRepository.save(new Lugar(null, "Estadio", "Av. Central", "CABA", -34.0, -58.0, List.of()));
        Evento evento = Evento.borrador("org-1", lugar.getId(), lugar.getCiudad(), "Festival", "Desc",
                "MUSICA", List.of("Artista"), Instant.parse("2027-05-16T00:00:00Z"), 4,
                List.of(new EventoSector("sector-a", "Platea", TipoSector.NUMERADO, 1, 2, null, null)));
        evento.setEstado(EstadoEvento.AGOTADO);
        eventoRepository.save(evento);
        when(reservasClient.consultarDisponibilidad(evento.getId()))
                .thenThrow(new ReservasClientException("timeout"));

        var detalle = consultaService.detalle(evento.getId(), "cliente-1", "CLIENTE");

        assertEquals("agotado", detalle.estado());
        assertEquals(null, detalle.sectores().getFirst().disponibilidad());
    }

    @Test
    void sinEventosPublicadosNiAgotadosLaBusquedaDevuelvePaginaVacia() {
        guardarEvento("org-1", "CABA", "Borrador", "Desc", "MUSICA", "Artista",
                "2027-05-16T00:00:00Z", EstadoEvento.BORRADOR, null);
        guardarEvento("org-1", "CABA", "Cancelado", "Desc", "MUSICA", "Artista",
                "2027-05-16T00:00:00Z", EstadoEvento.CANCELADO, "1000");
        var respuesta = consultaService.buscar(null, null, null, null, null, null,
                null, null, null, null, null, "cliente-1", "CLIENTE");
        assertEquals(0, respuesta.totalElementos());
        assertTrue(respuesta.contenido().isEmpty());
        assertEquals(20, respuesta.tamano());
    }

    private Evento guardarEvento(String organizador, String ciudad, String nombre, String descripcion,
            String tipo, String participante, String fecha, EstadoEvento estado, String precio) {
        Evento evento = Evento.borrador(organizador, "lugar-1", ciudad, nombre, descripcion, tipo,
                List.of(participante), Instant.parse(fecha), 4, List.of());
        evento.setEstado(estado);
        if (precio != null) evento.setPrecioMinimo(new BigDecimal(precio));
        return eventoRepository.save(evento);
    }
}
