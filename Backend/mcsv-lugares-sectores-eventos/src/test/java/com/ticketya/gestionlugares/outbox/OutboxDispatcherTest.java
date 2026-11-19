package com.ticketya.gestionlugares.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;

@ExtendWith(MockitoExtension.class)
class OutboxDispatcherTest {

    private static final Instant AHORA = Instant.parse("2026-11-18T18:00:00Z");

    @Mock
    private OutboxAvisoRepository outboxRepository;

    @Mock
    private OutboxHttpClient httpClient;

    private AvisoOutbox aviso;
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void configurarDispatcher() {
        aviso = aviso(1);
        when(outboxRepository.reclamarSiguiente(AHORA, AHORA.plusSeconds(60), OutboxDispatcher.MAX_INTENTOS))
                .thenReturn(Optional.of(aviso));
        dispatcher = new OutboxDispatcher(outboxRepository, httpClient,
                Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202, 204})
    void respuestas2xxMarcanElAvisoComoEntregado(int status) {
        when(httpClient.enviar(aviso)).thenReturn(status);

        assertTrue(dispatcher.procesarSiguiente());

        assertEquals(EstadoAvisoOutbox.ENTREGADO, aviso.getEstado());
        assertEquals(AHORA, aviso.getEntregadoEn());
        assertNull(aviso.getUltimoError());
        assertNull(aviso.getLeaseHasta());
        verify(outboxRepository).save(aviso);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409})
    void errores4xxDefinitivosNoSeReintentan(int status) {
        when(httpClient.enviar(aviso)).thenReturn(status);

        dispatcher.procesarSiguiente();

        assertEquals(EstadoAvisoOutbox.ERROR_FINAL, aviso.getEstado());
        assertEquals("El destino respondió HTTP " + status, aviso.getUltimoError());
        verify(httpClient, times(1)).enviar(aviso);
        verify(outboxRepository).save(aviso);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 599})
    void erroresTransitoriosHttpSeReintentanConLaEsperaBase(int status) {
        when(httpClient.enviar(aviso)).thenReturn(status);

        dispatcher.procesarSiguiente();

        assertEquals(EstadoAvisoOutbox.PENDIENTE, aviso.getEstado());
        assertEquals(AHORA.plusSeconds(30), aviso.getProximoIntento());
        assertEquals("El destino respondió HTTP " + status, aviso.getUltimoError());
    }

    @Test
    void timeoutSeReintentaConLaEsperaBase() {
        when(httpClient.enviar(aviso)).thenThrow(new ResourceAccessException(
                "read timed out", new SocketTimeoutException("read timed out")));

        dispatcher.procesarSiguiente();

        assertEquals(EstadoAvisoOutbox.PENDIENTE, aviso.getEstado());
        assertEquals(AHORA.plusSeconds(30), aviso.getProximoIntento());
        assertTrue(aviso.getUltimoError().contains("SocketTimeoutException"));
    }

    @Test
    void errorDeConexionSeReintentaConLaEsperaBase() {
        when(httpClient.enviar(aviso)).thenThrow(new ResourceAccessException(
                "connection refused", new ConnectException("connection refused")));

        dispatcher.procesarSiguiente();

        assertEquals(EstadoAvisoOutbox.PENDIENTE, aviso.getEstado());
        assertEquals(AHORA.plusSeconds(30), aviso.getProximoIntento());
        assertTrue(aviso.getUltimoError().contains("ConnectException"));
    }

    @Test
    void calculaBackoffExponencialYLoLimitaAQuinceMinutos() {
        aviso.setIntentos(2);
        when(httpClient.enviar(aviso)).thenReturn(500);

        dispatcher.procesarSiguiente();

        assertEquals(AHORA.plusSeconds(60), aviso.getProximoIntento());

        aviso = aviso(6);
        when(outboxRepository.reclamarSiguiente(AHORA, AHORA.plusSeconds(60), OutboxDispatcher.MAX_INTENTOS))
                .thenReturn(Optional.of(aviso));
        when(httpClient.enviar(aviso)).thenReturn(500);

        dispatcher.procesarSiguiente();

        assertEquals(AHORA.plus(Duration.ofMinutes(15)), aviso.getProximoIntento());
    }

    @Test
    void falloEnElUltimoIntentoPasaDirectamenteAErrorFinal() {
        aviso = aviso(OutboxDispatcher.MAX_INTENTOS);
        when(outboxRepository.reclamarSiguiente(AHORA, AHORA.plusSeconds(60), OutboxDispatcher.MAX_INTENTOS))
                .thenReturn(Optional.of(aviso));
        when(httpClient.enviar(aviso)).thenReturn(500);

        dispatcher.procesarSiguiente();

        assertEquals(EstadoAvisoOutbox.ERROR_FINAL, aviso.getEstado());
        assertEquals("El destino respondió HTTP 500", aviso.getUltimoError());
    }

    @Test
    void respuestas3xxSonDefinitivas() {
        when(httpClient.enviar(aviso)).thenReturn(302);

        dispatcher.procesarSiguiente();

        assertEquals(EstadoAvisoOutbox.ERROR_FINAL, aviso.getEstado());
        assertEquals("El destino respondió HTTP 302", aviso.getUltimoError());
    }

    private AvisoOutbox aviso(int intentos) {
        AvisoOutbox aviso = new AvisoOutbox();
        aviso.setId("aviso-evento-1");
        aviso.setTipo("PRUEBA");
        aviso.setEventoId("evento-1");
        aviso.setDestino("http://destino.test/avisos");
        aviso.setPayload(Map.of("eventoId", "evento-1", "detalle", "notificacion"));
        aviso.setEstado(EstadoAvisoOutbox.PROCESANDO);
        aviso.setIntentos(intentos);
        aviso.setProximoIntento(AHORA);
        aviso.setLeaseHasta(AHORA.plusSeconds(60));
        return aviso;
    }
}
