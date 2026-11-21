package ticketya.servicio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ticketya.RelojDePrueba;
import ticketya.cliente.UsuariosCliente;
import ticketya.envio.EnvioDeCorreo;
import ticketya.envio.FalloTemporalException;
import ticketya.modelo.EstadoNotificacion;
import ticketya.modelo.Notificacion;
import ticketya.modelo.TipoNotificacion;
import ticketya.repositorio.NotificacionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "notificaciones.reintentos.maximo-intentos=3",
        "notificaciones.reintentos.espera-base=30s",
        "notificaciones.reintentos.espera-maxima=10m"
})
@ActiveProfiles("test")
@Import(RelojDePrueba.Config.class)
class ReintentosTest {

    @Autowired
    EnvioService servicio;

    @Autowired
    NotificacionRepository repositorio;

    @Autowired
    RelojDePrueba reloj;

    @MockitoBean
    UsuariosCliente usuarios;

    @MockitoBean
    EnvioDeCorreo envio;

    UUID id;

    @BeforeEach
    void preparar() {
        reloj.poner(RelojDePrueba.INICIO);
        id = repositorio.save(Notificacion.nueva("ac-1", TipoNotificacion.EVENTO_CANCELADO, "u-1", "evento-1",
                "corr-1", 1, reloj.instant(), Map.of("evento", "Recital"), reloj.instant())).getId();
        when(usuarios.correoDe("u-1")).thenReturn("ana@example.com");
    }

    @AfterEach
    void limpiar() {
        repositorio.deleteAll();
    }

    Notificacion leer() {
        return repositorio.findById(id).orElseThrow();
    }

    @Test
    void falloTemporalDeSmtpVuelveAPendienteConEsperaCreciente() {
        doThrow(new FalloTemporalException("SMTP no disponible")).when(envio).enviar(any());

        servicio.procesarPendientes();
        Notificacion n = leer();
        assertThat(n.getEstado()).isEqualTo(EstadoNotificacion.PENDIENTE);
        assertThat(n.getIntentos()).isEqualTo(1);
        assertThat(n.getProximoIntento()).isEqualTo(RelojDePrueba.INICIO.plusSeconds(30));
        assertThat(n.getUltimoError()).isEqualTo("SMTP no disponible");
        assertThat(n.getVenceProcesamiento()).isNull();

        // Antes del próximo intento no se vuelve a tomar
        reloj.avanzar(Duration.ofSeconds(29));
        assertThat(servicio.procesarPendientes()).isZero();

        reloj.avanzar(Duration.ofSeconds(1));
        Instant segundoIntento = reloj.instant();
        assertThat(servicio.procesarPendientes()).isEqualTo(1);
        assertThat(leer().getIntentos()).isEqualTo(2);
        assertThat(leer().getProximoIntento()).isEqualTo(segundoIntento.plusSeconds(60));
    }

    @Test
    void falloTemporalDeGestionDeUsuariosTambienSeReintenta() {
        when(usuarios.correoDe("u-1")).thenThrow(new FalloTemporalException("Circuit breaker hacia Gestión de usuarios abierto"));

        servicio.procesarPendientes();

        assertThat(leer().getEstado()).isEqualTo(EstadoNotificacion.PENDIENTE);
        assertThat(leer().getUltimoError()).contains("Circuit breaker");
    }

    @Test
    void alAgotarLosIntentosPasaAErrorFinalConservandoElUltimoError() {
        doThrow(new FalloTemporalException("intento 1"), new FalloTemporalException("intento 2"),
                new FalloTemporalException("intento 3")).when(envio).enviar(any());

        for (int i = 0; i < 3; i++) {
            servicio.procesarPendientes();
            reloj.avanzar(Duration.ofMinutes(10));
        }

        Notificacion n = leer();
        assertThat(n.getEstado()).isEqualTo(EstadoNotificacion.ERROR_FINAL);
        assertThat(n.getIntentos()).isEqualTo(3);
        assertThat(n.getUltimoError()).isEqualTo("intento 3");
        assertThat(servicio.procesarPendientes()).isZero();
    }

    @Test
    void siUnReintentoSaleBienQuedaEnviada() {
        doThrow(new FalloTemporalException("SMTP no disponible")).doNothing().when(envio).enviar(any());

        servicio.procesarPendientes();
        reloj.avanzar(Duration.ofSeconds(30));
        servicio.procesarPendientes();

        Notificacion n = leer();
        assertThat(n.getEstado()).isEqualTo(EstadoNotificacion.ENVIADA);
        assertThat(n.getIntentos()).isEqualTo(2);
        assertThat(n.getUltimoError()).isNull();
    }

    @Test
    void unErrorInesperadoSeTrataComoTemporal() {
        doThrow(new IllegalStateException("bug")).when(envio).enviar(any());

        servicio.procesarPendientes();

        assertThat(leer().getEstado()).isEqualTo(EstadoNotificacion.PENDIENTE);
        assertThat(leer().getUltimoError()).contains("Error inesperado");
    }

    @Test
    void errorMuyLargoSeRecorta() {
        doThrow(new FalloTemporalException("x".repeat(5000))).when(envio).enviar(any());

        servicio.procesarPendientes();

        assertThat(leer().getUltimoError()).hasSize(Notificacion.LARGO_MAXIMO_ERROR);
    }
}
