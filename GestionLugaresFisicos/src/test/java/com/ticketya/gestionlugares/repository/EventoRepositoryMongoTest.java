package com.ticketya.gestionlugares.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

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

import com.mongodb.client.MongoClient;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.config.MongoDecimalConfiguration;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataMongoTest
@Import(MongoDecimalConfiguration.class)
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
    @Autowired private MongoClient mongoClient;

    @BeforeEach
    void limpiarColeccion() {
        eventoRepository.deleteAll();
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
}
