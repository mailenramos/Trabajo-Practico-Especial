package com.ticketya.gestionlugares.outbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import com.ticketya.gestionlugares.config.ServicioToken;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;

@ExtendWith(OutputCaptureExtension.class)
class OutboxPausaTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void sinTokenNoBarreReclamaNiEnvia(String token, CapturedOutput output) {
        OutboxAvisoRepository repository = mock(OutboxAvisoRepository.class);
        OutboxHttpClient cliente = mock(OutboxHttpClient.class);
        Clock clock = Clock.systemUTC();
        OutboxDispatcher dispatcher = new OutboxDispatcher(repository, cliente, clock,
                new ServicioToken(token, new MockEnvironment()), mock(EventoCancelacionOutboxService.class));
        new OutboxScheduler(repository, dispatcher, clock).procesarPendientes();
        new OutboxScheduler(repository, dispatcher, clock).procesarPendientes();
        assertFalse(dispatcher.procesarAviso("aviso-1"));
        assertFalse(dispatcher.procesarSiguiente());
        verifyNoInteractions(repository, cliente);
        org.assertj.core.api.Assertions.assertThat(output.getOut())
                .containsOnlyOnce("SERVICIO_TOKEN no configurada");
    }
}
