package com.ticketya.gestionlugares.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mongodb.client.MongoClient;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.Sector;
import com.ticketya.gestionlugares.domain.TipoSector;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataMongoTest
@Testcontainers(disabledWithoutDocker = true)
class LugarRepositoryMongoTest {

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl()
                .replace("/test", "/ticketya_lugares"));
    }

    @Autowired
    private LugarRepository lugarRepository;

    @Autowired
    private MongoClient mongoClient;

    @BeforeEach
    void limpiarColeccion() {
        lugarRepository.deleteAll();
    }

    @Test
    void persisteLugarConSectoresEmbebidosSinDocumentosDeAsiento() {
        Lugar lugar = new Lugar("507f1f77bcf86cd799439011", "Teatro Central",
                "Av. Corrientes 1234", "Buenos Aires", -34.6037, -58.3816,
                List.of(new Sector("550e8400-e29b-41d4-a716-446655440000", "Platea",
                        TipoSector.NUMERADO, 20, 30, null)));

        lugarRepository.save(lugar);

        Lugar recuperado = lugarRepository.findById(lugar.getId()).orElseThrow();
        assertEquals(1, recuperado.getSectores().size());
        assertEquals(20, recuperado.getSectores().getFirst().filas());
        Object idAlmacenado = mongoClient.getDatabase("ticketya_lugares")
                .getCollection("lugares").find().first().get("_id");
        assertInstanceOf(ObjectId.class, idAlmacenado);
        List<String> colecciones = mongoClient.getDatabase("ticketya_lugares")
                .listCollectionNames().into(new ArrayList<>());
        org.junit.jupiter.api.Assertions.assertFalse(colecciones.contains("asientos"));
    }
}
