package com.ticketya.gestionlugares.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.ticketya.gestionlugares.config.MongoDecimalConfiguration;
import com.ticketya.gestionlugares.config.MongoTransactionConfiguration;
import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataMongoTest
@Import({MongoDecimalConfiguration.class, MongoTransactionConfiguration.class,
        OutboxAvisoRepositoryMongoTest.TransactionTestConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class OutboxAvisoRepositoryMongoTest {

    private static final Instant AHORA = Instant.parse("2026-11-18T18:00:00Z");

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl()
                .replace("/test", "/ticketya_lugares"));
    }

    @Autowired private OutboxAvisoRepository outboxRepository;
    @Autowired private EventoRepository eventoRepository;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private TransactionWriter transactionWriter;
    @Autowired private org.springframework.data.mongodb.MongoTransactionManager transactionManager;

    @BeforeEach
    void limpiarColecciones() {
        outboxRepository.deleteAll();
        eventoRepository.deleteAll();
    }

    @Test
    void configuraTransaccionesMongoYRevierteAmbosDocumentosSiFallaLaInsercionDelAviso() {
        assertNotNull(transactionManager);
        outboxRepository.insert(aviso("cancelacion-evento-existente", AHORA, 0,
                EstadoAvisoOutbox.PENDIENTE, null));
        Evento evento = evento("evento-rollback");
        AvisoOutbox avisoDuplicado = aviso("cancelacion-evento-existente", AHORA, 0,
                EstadoAvisoOutbox.PENDIENTE, null);

        assertThrows(DuplicateKeyException.class,
                () -> transactionWriter.guardarEventoYAviso(evento, avisoDuplicado));

        assertFalse(eventoRepository.existsById(evento.getId()));
        assertEquals(1, outboxRepository.count());
    }

    @Test
    void creaIndiceUnicoPorIdEIndicesParaSeleccionarAvisosElegibles() {
        List<IndexInfo> indices = mongoTemplate.indexOps(AvisoOutbox.class).getIndexInfo();

        assertTrue(indices.stream().anyMatch(index -> index.getName().equals("_id_")),
                indices.toString());
        assertTrue(indices.stream().anyMatch(index -> index.getName().equals("idx_outbox_estado_proximo_intento")),
                indices.toString());
        assertTrue(indices.stream().anyMatch(index -> index.getName().equals("idx_outbox_estado_lease")),
                indices.toString());

        outboxRepository.insert(aviso("cancelacion-evento-unico", AHORA, 0,
                EstadoAvisoOutbox.PENDIENTE, null));
        assertThrows(DuplicateKeyException.class,
                () -> outboxRepository.insert(aviso("cancelacion-evento-unico", AHORA, 0,
                        EstadoAvisoOutbox.PENDIENTE, null)));
    }

    @Test
    void reclamaAvisoPendienteAtomicaYLoPasaAProcesando() {
        AvisoOutbox pendiente = aviso("cancelacion-evento-pendiente", AHORA.minusSeconds(1), 0,
                EstadoAvisoOutbox.PENDIENTE, null);
        outboxRepository.insert(pendiente);

        AvisoOutbox reclamado = outboxRepository.reclamarSiguiente(AHORA, AHORA.plusSeconds(60), 8)
                .orElseThrow();

        assertEquals(pendiente.getId(), reclamado.getId());
        assertEquals(EstadoAvisoOutbox.PROCESANDO, reclamado.getEstado());
        assertEquals(1, reclamado.getIntentos());
        assertEquals(AHORA.plusSeconds(60), reclamado.getLeaseHasta());
        assertTrue(outboxRepository.reclamarSiguiente(AHORA, AHORA.plusSeconds(60), 8).isEmpty());
    }

    @Test
    void recuperaAvisoProcesandoConLeaseVencidoYCuentaNuevoIntento() {
        AvisoOutbox expirado = aviso("cancelacion-evento-expirado", AHORA.minusSeconds(5), 2,
                EstadoAvisoOutbox.PROCESANDO, AHORA.minusSeconds(1));
        outboxRepository.insert(expirado);

        AvisoOutbox recuperado = outboxRepository.reclamarSiguiente(AHORA, AHORA.plusSeconds(60), 8)
                .orElseThrow();

        assertEquals(expirado.getId(), recuperado.getId());
        assertEquals(EstadoAvisoOutbox.PROCESANDO, recuperado.getEstado());
        assertEquals(3, recuperado.getIntentos());
        assertEquals(AHORA.plusSeconds(60), recuperado.getLeaseHasta());
    }

    @Test
    void reclamaElAvisoConProximoIntentoMasAntiguoPrimero() {
        outboxRepository.insert(aviso("cancelacion-evento-posterior", AHORA.minusSeconds(1), 0,
                EstadoAvisoOutbox.PENDIENTE, null));
        outboxRepository.insert(aviso("cancelacion-evento-anterior", AHORA.minusSeconds(5), 0,
                EstadoAvisoOutbox.PENDIENTE, null));

        AvisoOutbox reclamado = outboxRepository.reclamarSiguiente(AHORA, AHORA.plusSeconds(60), 8)
                .orElseThrow();

        assertEquals("cancelacion-evento-anterior", reclamado.getId());
    }

    @Test
    void workersConcurrentesReclamanUnAvisoUnaSolaVez() throws Exception {
        outboxRepository.insert(aviso("cancelacion-evento-concurrente", AHORA.minusSeconds(1), 0,
                EstadoAvisoOutbox.PENDIENTE, null));
        int cantidadWorkers = 8;
        ExecutorService workers = Executors.newFixedThreadPool(cantidadWorkers);
        CountDownLatch listos = new CountDownLatch(cantidadWorkers);
        CountDownLatch iniciar = new CountDownLatch(1);
        try {
            List<Future<Boolean>> resultados = java.util.stream.IntStream.range(0, cantidadWorkers)
                    .mapToObj(worker -> workers.submit(() -> {
                        listos.countDown();
                        if (!iniciar.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Los workers no iniciaron a tiempo");
                        }
                        return outboxRepository.reclamarSiguiente(AHORA, AHORA.plusSeconds(60), 8).isPresent();
                    })).toList();

            assertTrue(listos.await(10, TimeUnit.SECONDS));
            iniciar.countDown();
            long avisosReclamados = 0;
            for (Future<Boolean> resultado : resultados) {
                if (resultado.get(20, TimeUnit.SECONDS)) avisosReclamados++;
            }

            assertEquals(1, avisosReclamados);
            AvisoOutbox persistido = outboxRepository.findById("cancelacion-evento-concurrente").orElseThrow();
            assertEquals(EstadoAvisoOutbox.PROCESANDO, persistido.getEstado());
            assertEquals(1, persistido.getIntentos());
        } finally {
            iniciar.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void noReclamaUnAvisoQueYaAlcanzoElMaximoDeIntentos() {
        outboxRepository.insert(aviso("cancelacion-evento-agotado", AHORA.minusSeconds(1), 8,
                EstadoAvisoOutbox.PENDIENTE, null));

        assertTrue(outboxRepository.reclamarSiguiente(AHORA, AHORA.plusSeconds(60), 8).isEmpty());
    }

    @Test
    void marcaPendienteConIntentosAgotadosComoErrorFinal() {
        outboxRepository.insert(aviso("cancelacion-evento-pendiente-agotado", AHORA.minusSeconds(1), 8,
                EstadoAvisoOutbox.PENDIENTE, null));

        long marcados = outboxRepository.marcarAvisosAgotados(AHORA, 8);

        assertEquals(1, marcados);
        AvisoOutbox aviso = outboxRepository.findById("cancelacion-evento-pendiente-agotado").orElseThrow();
        assertEquals(EstadoAvisoOutbox.ERROR_FINAL, aviso.getEstado());
        assertEquals("intentos agotados", aviso.getUltimoError());
        assertEquals(null, aviso.getLeaseHasta());
    }

    @Test
    void marcaErrorFinalUnaCaidaTrasReclamarElUltimoIntentoCuandoVenceElLease() {
        outboxRepository.insert(aviso("cancelacion-evento-caido", AHORA.minusSeconds(1), 7,
                EstadoAvisoOutbox.PENDIENTE, null));
        AvisoOutbox ultimoIntento = outboxRepository.reclamarSiguiente(
                AHORA, AHORA.plusSeconds(60), 8).orElseThrow();
        assertEquals(8, ultimoIntento.getIntentos());
        assertEquals(0, outboxRepository.marcarAvisosAgotados(AHORA.plusSeconds(59), 8));

        long marcados = outboxRepository.marcarAvisosAgotados(AHORA.plusSeconds(60), 8);

        assertEquals(1, marcados);
        AvisoOutbox agotado = outboxRepository.findById("cancelacion-evento-caido").orElseThrow();
        assertEquals(EstadoAvisoOutbox.ERROR_FINAL, agotado.getEstado());
        assertEquals("intentos agotados", agotado.getUltimoError());
        assertEquals(8, agotado.getIntentos());
        assertEquals(null, agotado.getLeaseHasta());
    }

    private AvisoOutbox aviso(String id, Instant proximoIntento, int intentos,
            EstadoAvisoOutbox estado, Instant leaseHasta) {
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setId(id);
        aviso.setTipo("CANCELACION");
        aviso.setEventoId(id);
        aviso.setDestino("compras:/eventos-cancelados");
        aviso.setPayload(Map.of("eventoId", id));
        aviso.setEstado(estado);
        aviso.setIntentos(intentos);
        aviso.setProximoIntento(proximoIntento);
        aviso.setCreadoEn(AHORA.minusSeconds(10));
        aviso.setActualizadoEn(AHORA.minusSeconds(10));
        aviso.setLeaseHasta(leaseHasta);
        return aviso;
    }

    private Evento evento(String id) {
        Evento evento = Evento.borrador("organizador-1", "lugar-1", "CABA", "Evento", "Desc",
                "MUSICA", List.of(), AHORA.plusSeconds(3600), 4, List.of());
        evento.setId(id);
        evento.setEstado(EstadoEvento.PUBLICADO);
        return evento;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionTestConfiguration {

        @Bean
        TransactionWriter transactionWriter(EventoRepository eventoRepository,
                OutboxAvisoRepository outboxAvisoRepository) {
            return new TransactionWriter(eventoRepository, outboxAvisoRepository);
        }
    }

    public static class TransactionWriter {

        private final EventoRepository eventoRepository;
        private final OutboxAvisoRepository outboxAvisoRepository;

        TransactionWriter(EventoRepository eventoRepository, OutboxAvisoRepository outboxAvisoRepository) {
            this.eventoRepository = eventoRepository;
            this.outboxAvisoRepository = outboxAvisoRepository;
        }

        @Transactional
        public void guardarEventoYAviso(Evento evento, AvisoOutbox aviso) {
            eventoRepository.save(evento);
            outboxAvisoRepository.insert(aviso);
        }
    }
}
