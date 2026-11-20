package ticketya.cliente.rest;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ticketya.config.ResilienciaConfig;
import ticketya.envio.FalloDefinitivoException;
import ticketya.envio.FalloTemporalException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsuariosClienteRestTest {

    ServidorDePrueba ms6;
    CircuitBreaker circuito;
    UsuariosClienteRest cliente;

    @BeforeEach
    void preparar() throws Exception {
        ms6 = new ServidorDePrueba();
        circuito = CircuitBreaker.of("usuarios-test", ResilienciaConfig.configuracionBase());
        cliente = new UsuariosClienteRest(ms6.url(), Duration.ofSeconds(1), Duration.ofMillis(300), "token-de-prueba",
                circuito);
    }

    @AfterEach
    void cerrar() {
        ms6.close();
    }

    @Test
    void devuelveElCorreoYEnviaElTokenDeServicio() {
        ms6.responder(200, "{\"correo\":\" ana@example.com \",\"nombre\":\"Ana\"}");

        assertThat(cliente.correoDe("u-1")).isEqualTo("ana@example.com");
        assertThat(ms6.ruta).isEqualTo("/internal/usuarios/u-1/contacto");
        assertThat(ms6.encabezados.getFirst("X-Service-Token")).isEqualTo("token-de-prueba");
    }

    @Test
    void usuarioInexistenteEsDefinitivo() {
        ms6.responder(404, "{\"error\":\"USUARIO_NO_ENCONTRADO\"}");

        assertThatThrownBy(() -> cliente.correoDe("u-1")).isInstanceOf(FalloDefinitivoException.class);
    }

    @Test
    void usuarioSinCorreoEsDefinitivo() {
        ms6.responder(200, "{\"correo\":\"\"}");
        assertThatThrownBy(() -> cliente.correoDe("u-1")).isInstanceOf(FalloDefinitivoException.class);

        ms6.responder(200, "{}");
        assertThatThrownBy(() -> cliente.correoDe("u-1")).isInstanceOf(FalloDefinitivoException.class);
    }

    @Test
    void errorDelServidorEsTemporal() {
        ms6.responder(503, "");

        assertThatThrownBy(() -> cliente.correoDe("u-1")).isInstanceOf(FalloTemporalException.class)
                .hasMessageContaining("503");
    }

    @Test
    void timeoutDeLecturaEsTemporal() {
        ms6.responder(200, "{\"correo\":\"ana@example.com\"}");
        ms6.demoraMs = 1500;

        assertThatThrownBy(() -> cliente.correoDe("u-1")).isInstanceOf(FalloTemporalException.class);
    }

    @Test
    void servicioCaidoEsTemporal() {
        ms6.close();

        assertThatThrownBy(() -> cliente.correoDe("u-1")).isInstanceOf(FalloTemporalException.class);
    }

    @Test
    void conElCircuitoAbiertoNoLlamaAMs6() {
        ms6.responder(500, "");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> cliente.correoDe("u-1")).isInstanceOf(FalloTemporalException.class);
        }
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int pedidosAntes = ms6.pedidos.get();

        assertThatThrownBy(() -> cliente.correoDe("u-1")).isInstanceOf(FalloTemporalException.class)
                .hasMessageContaining("Circuit breaker");
        assertThat(ms6.pedidos.get()).isEqualTo(pedidosAntes);
    }

    @Test
    void losUsuariosInexistentesNoAbrenElCircuito() {
        ms6.responder(404, "");
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> cliente.correoDe("u-1")).isInstanceOf(FalloDefinitivoException.class);
        }
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void sinTokenConfiguradoNoEnviaElEncabezado() {
        var sinToken = new UsuariosClienteRest(ms6.url(), Duration.ofSeconds(1), Duration.ofSeconds(1), "",
                CircuitBreaker.of("otro", CircuitBreakerConfig.ofDefaults()));
        ms6.responder(200, "{\"correo\":\"ana@example.com\"}");

        sinToken.correoDe("u-1");
        assertThat(ms6.encabezados.containsKey("X-Service-Token")).isFalse();
    }
}
