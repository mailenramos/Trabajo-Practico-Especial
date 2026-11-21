package ticketya.envio;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Envío por SMTP (spring-boot-starter-mail). Los timeouts de conexión y lectura se configuran
 * con spring.mail.properties.mail.smtp.*; el circuit breaker evita insistir con un SMTP caído.
 * <p>
 * Si SMTP acepta el correo pero la confirmación se pierde, el envío se ve como fallo y se
 * reintenta: el destinatario puede recibirlo dos veces (no hay entrega exactamente una vez).
 */
public class EnvioDeCorreoSmtp implements EnvioDeCorreo {

    private final JavaMailSender smtp;
    private final String remitente;
    private final CircuitBreaker circuito;

    public EnvioDeCorreoSmtp(JavaMailSender smtp, String remitente, CircuitBreaker circuito) {
        this.smtp = smtp;
        this.remitente = remitente;
        this.circuito = circuito;
    }

    @Override
    public void enviar(Correo correo) {
        SimpleMailMessage mensaje = new SimpleMailMessage();
        mensaje.setFrom(remitente);
        mensaje.setTo(correo.destinatario());
        mensaje.setSubject(correo.asunto());
        mensaje.setText(correo.cuerpo());
        try {
            circuito.executeRunnable(() -> enviarPorSmtp(mensaje));
        } catch (CallNotPermittedException e) {
            throw new FalloTemporalException("Circuit breaker hacia SMTP abierto", e);
        }
    }

    private void enviarPorSmtp(SimpleMailMessage mensaje) {
        try {
            smtp.send(mensaje);
        } catch (MailParseException | MailPreparationException e) {
            // El mensaje no se puede armar (por ejemplo, dirección mal formada): reintentar no sirve
            throw new FalloDefinitivoException("Correo inválido: " + e.getMessage(), e);
        } catch (MailException e) {
            throw new FalloTemporalException("Falla al enviar por SMTP: " + e.getMessage(), e);
        }
    }
}
