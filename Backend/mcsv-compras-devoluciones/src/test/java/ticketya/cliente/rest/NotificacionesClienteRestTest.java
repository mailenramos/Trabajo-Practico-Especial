package ticketya.cliente.rest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ticketya.cliente.NotificacionesCliente.Aviso;
import ticketya.cliente.NotificacionesCliente.AvisoRechazadoException;
import ticketya.cliente.ServicioExternoException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Envío de un aviso a Notificaciones: ruta, token, cuerpo y clasificación de las fallas. */
class NotificacionesClienteRestTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private ServidorDePrueba servidor;
    private NotificacionesClienteRest cliente;

    @BeforeEach
    void levantar() throws Exception {
        servidor = new ServidorDePrueba();
        servidor.responder(202, "{\"id\":\"n-1\",\"estado\":\"PENDIENTE\",\"duplicado\":false}");
        cliente = new NotificacionesClienteRest(servidor.url(), "secreto", Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @AfterEach
    void apagar() {
        servidor.close();
    }

    private Aviso aviso() {
        return new Aviso("compra-confirmada-c-1", "COMPRA_CONFIRMADA", 1, Instant.parse("2026-11-20T12:00:00Z"),
                "c-1", "cliente-1", "corr-1", json.readTree("{\"evento\":\"Recital\",\"cantidad\":2}"));
    }

    @Test
    void enviaElAvisoConElTokenYElContratoAcordado() {
        cliente.enviar(aviso());

        assertThat(servidor.metodo).isEqualTo("POST");
        assertThat(servidor.ruta).isEqualTo("/internal/notificaciones");
        assertThat(servidor.encabezados.getFirst("X-Service-Token")).isEqualTo("secreto");
        assertThat(servidor.encabezados.getFirst("Content-Type")).startsWith("application/json");
        JsonNode cuerpo = json.readTree(servidor.cuerpoRecibido);
        assertThat(cuerpo.get("acontecimientoId").asString()).isEqualTo("compra-confirmada-c-1");
        assertThat(cuerpo.get("tipo").asString()).isEqualTo("COMPRA_CONFIRMADA");
        assertThat(cuerpo.get("versionEsquema").asInt()).isEqualTo(1);
        assertThat(cuerpo.get("ocurridoEn").asString()).isEqualTo("2026-11-20T12:00:00Z");
        assertThat(cuerpo.get("recursoId").asString()).isEqualTo("c-1");
        assertThat(cuerpo.get("usuarioId").asString()).isEqualTo("cliente-1");
        assertThat(cuerpo.get("correlacionId").asString()).isEqualTo("corr-1");
        assertThat(cuerpo.get("datos").get("evento").asString()).isEqualTo("Recital");
        assertThat(cuerpo.get("datos").get("cantidad").asInt()).isEqualTo(2);
    }

    @Test
    void unAvisoDuplicadoTambienEsExito() {
        servidor.responder(202, "{\"id\":\"n-1\",\"estado\":\"ENVIADA\",\"duplicado\":true}");

        cliente.enviar(aviso());
    }

    @Test
    void un400EsUnRechazoDefinitivo() {
        servidor.responder(400, "{\"status\":400,\"error\":\"VALIDACION\",\"mensaje\":\"inválido\"}");

        assertThatThrownBy(() -> cliente.enviar(aviso()))
                .isInstanceOf(AvisoRechazadoException.class)
                .hasMessageContaining("400").hasMessageContaining("VALIDACION");
    }

    @Test
    void un403Un500YLaFaltaDeRespuestaSonFallasTemporales() {
        servidor.responder(403, "{\"status\":403,\"error\":\"ACCESO_DENEGADO\"}");
        assertThatThrownBy(() -> cliente.enviar(aviso())).isInstanceOf(ServicioExternoException.class);

        servidor.responder(500, "{\"status\":500,\"error\":\"ERROR_INTERNO\"}");
        assertThatThrownBy(() -> cliente.enviar(aviso())).isInstanceOf(ServicioExternoException.class);

        NotificacionesClienteRest sinServidor = new NotificacionesClienteRest("http://localhost:1", "secreto",
                Duration.ofMillis(300), Duration.ofMillis(300));
        assertThatThrownBy(() -> sinServidor.enviar(aviso())).isInstanceOf(ServicioExternoException.class);
    }
}
