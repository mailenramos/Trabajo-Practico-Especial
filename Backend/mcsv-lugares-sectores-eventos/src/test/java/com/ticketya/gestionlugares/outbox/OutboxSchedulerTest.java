package com.ticketya.gestionlugares.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;

@SpringBootTest(properties = "outbox.dispatcher.interval=3600000")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class OutboxSchedulerTest {

    private static final Instant AHORA = Instant.parse("2026-11-18T18:00:00Z");

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl()
                .replace("/test", "/ticketya_lugares"));
    }

    @Autowired private OutboxScheduler scheduler;
    @Autowired private OutboxAvisoRepository outboxRepository;
    @MockBean private OutboxHttpClient httpClient;

    @BeforeEach
    void limpiarOutbox() {
        outboxRepository.deleteAll();
    }

    @Test
    void reclamaYEntregaAvisoPendienteConProximoIntentoVencido() {
        AvisoOutbox aviso = aviso("aviso-evento-vencido", AHORA.minusSeconds(1), 0);
        outboxRepository.insert(aviso);
        when(httpClient.enviar(any(AvisoOutbox.class))).thenReturn(204);

        scheduler.procesarPendientes();

        AvisoOutbox entregado = outboxRepository.findById(aviso.getId()).orElseThrow();
        assertEquals(EstadoAvisoOutbox.ENTREGADO, entregado.getEstado());
        assertEquals(1, entregado.getIntentos());
        verify(httpClient).enviar(any(AvisoOutbox.class));
    }

    @Test
    void cadaPasadaMarcaIntentosAgotadosAntesDeDespachar() {
        AvisoOutbox agotado = aviso("aviso-agotado",
                Instant.now().minusSeconds(1), OutboxDispatcher.MAX_INTENTOS);
        outboxRepository.insert(agotado);

        scheduler.procesarPendientes();

        AvisoOutbox persistido = outboxRepository.findById(agotado.getId()).orElseThrow();
        assertEquals(EstadoAvisoOutbox.ERROR_FINAL, persistido.getEstado());
        assertEquals("intentos agotados", persistido.getUltimoError());
        verify(httpClient, never()).enviar(any(AvisoOutbox.class));
    }

    @Test
    void limitaCadaPasadaACienAvisos() {
        List<AvisoOutbox> avisos = java.util.stream.IntStream.range(0,
                OutboxScheduler.MAX_AVISOS_POR_PASADA + 1)
                .mapToObj(index -> aviso("aviso-" + index, Instant.now().minusSeconds(1), 0))
                .toList();
        outboxRepository.insert(avisos);
        when(httpClient.enviar(any(AvisoOutbox.class))).thenReturn(204);

        scheduler.procesarPendientes();

        long entregados = outboxRepository.findAll().stream()
                .filter(aviso -> aviso.getEstado() == EstadoAvisoOutbox.ENTREGADO).count();
        long pendientes = outboxRepository.findAll().stream()
                .filter(aviso -> aviso.getEstado() == EstadoAvisoOutbox.PENDIENTE).count();
        assertEquals(OutboxScheduler.MAX_AVISOS_POR_PASADA, entregados);
        assertEquals(1, pendientes);
        verify(httpClient, times(OutboxScheduler.MAX_AVISOS_POR_PASADA)).enviar(any(AvisoOutbox.class));
    }

    private AvisoOutbox aviso(String id, Instant proximoIntento, int intentos) {
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setId(id);
        aviso.setTipo("PRUEBA");
        aviso.setEventoId(id);
        aviso.setDestino("http://destino.test/avisos");
        aviso.setPayload(Map.of("eventoId", id));
        aviso.setEstado(EstadoAvisoOutbox.PENDIENTE);
        aviso.setIntentos(intentos);
        aviso.setProximoIntento(proximoIntento);
        aviso.setCreadoEn(AHORA.minusSeconds(10));
        aviso.setActualizadoEn(AHORA.minusSeconds(10));
        return aviso;
    }
}
