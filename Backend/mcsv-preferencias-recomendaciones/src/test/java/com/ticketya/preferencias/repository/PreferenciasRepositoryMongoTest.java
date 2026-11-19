package com.ticketya.preferencias.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.ticketya.preferencias.domain.Preferencia;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PreferenciasRepositoryMongoTest {
    @Container
    private static final GenericContainer<?> MONGO = new GenericContainer<>(DockerImageName.parse("mongo:8.0"))
            .withExposedPorts(27017)
            .withCommand("mongod", "--bind_ip_all")
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1));

    @DynamicPropertySource
    static void configurarMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> "mongodb://" + MONGO.getHost() + ":"
                + MONGO.getMappedPort(27017) + "/ticketya_preferencias_test?directConnection=true");
    }

    @Autowired
    private PreferenciasRepository preferenciasRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void limpiarColeccion() {
        preferenciasRepository.deleteAll();
    }

    @Test
    void guardaYRecuperaDocumentoConUsuarioComoIdMongo() {
        Preferencia preferencia = preferencia("cliente-123", List.of("Rock"), List.of("Artista"),
                List.of("Córdoba"));

        preferenciasRepository.save(preferencia);

        assertEquals(preferencia, preferenciasRepository.findById("cliente-123").orElseThrow());
        Document documento = mongoTemplate.getCollection("preferencias").find().first();
        assertEquals("cliente-123", documento.getString("_id"));
        assertEquals(List.of("Rock"), documento.getList("tiposEvento", String.class));
    }

    @Test
    void reemplazaYEliminaPreferenciaPorUsuario() {
        preferenciasRepository.save(preferencia("cliente-123", List.of("Rock"), List.of("Artista"),
                List.of("Córdoba")));

        Preferencia reemplazo = preferencia("cliente-123", List.of("Jazz"), List.of(), List.of("Mendoza"));
        preferenciasRepository.save(reemplazo);

        assertEquals(1, preferenciasRepository.count());
        assertEquals(reemplazo, preferenciasRepository.findById("cliente-123").orElseThrow());

        preferenciasRepository.deleteById("cliente-123");

        assertTrue(preferenciasRepository.findById("cliente-123").isEmpty());
    }

    private Preferencia preferencia(String usuarioId, List<String> tipos, List<String> artistas,
            List<String> ciudades) {
        return new Preferencia(usuarioId, tipos, artistas, ciudades, Instant.parse("2026-11-18T12:00:00Z"));
    }
}
