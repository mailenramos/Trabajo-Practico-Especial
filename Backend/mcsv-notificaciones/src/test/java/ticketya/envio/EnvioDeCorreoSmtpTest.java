package ticketya.envio;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import ticketya.config.ResilienciaConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EnvioDeCorreoSmtpTest {

    JavaMailSender smtp;
    CircuitBreaker circuito;
    EnvioDeCorreoSmtp envio;
    final Correo correo = new Correo("ana@example.com", "Asunto", "Cuerpo");

    @BeforeEach
    void preparar() {
        smtp = mock(JavaMailSender.class);
        circuito = CircuitBreaker.of("smtp-test", ResilienciaConfig.configuracionBase());
        envio = new EnvioDeCorreoSmtp(smtp, "no-responder@ticketya.local", circuito);
    }

    @Test
    void armaElMensajeConRemitenteDestinatarioAsuntoYCuerpo() {
        envio.enviar(correo);

        ArgumentCaptor<SimpleMailMessage> enviado = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(smtp).send(enviado.capture());
        assertThat(enviado.getValue().getFrom()).isEqualTo("no-responder@ticketya.local");
        assertThat(enviado.getValue().getTo()).containsExactly("ana@example.com");
        assertThat(enviado.getValue().getSubject()).isEqualTo("Asunto");
        assertThat(enviado.getValue().getText()).isEqualTo("Cuerpo");
    }

    @Test
    void smtpNoDisponibleEsTemporal() {
        doThrow(new MailSendException("Connection refused")).when(smtp).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> envio.enviar(correo)).isInstanceOf(FalloTemporalException.class)
                .hasMessageContaining("Connection refused");
    }

    @Test
    void mensajeInvalidoEsDefinitivo() {
        doThrow(new MailParseException("dirección mal formada")).when(smtp).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> envio.enviar(correo)).isInstanceOf(FalloDefinitivoException.class);
    }

    @Test
    void conElCircuitoAbiertoNoLlamaASmtp() {
        doThrow(new MailSendException("timeout")).when(smtp).send(any(SimpleMailMessage.class));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> envio.enviar(correo)).isInstanceOf(FalloTemporalException.class);
        }
        verify(smtp, times(5)).send(any(SimpleMailMessage.class));
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        reset(smtp);

        assertThatThrownBy(() -> envio.enviar(correo)).isInstanceOf(FalloTemporalException.class)
                .hasMessageContaining("Circuit breaker");
        verifyNoInteractions(smtp);
    }
}
