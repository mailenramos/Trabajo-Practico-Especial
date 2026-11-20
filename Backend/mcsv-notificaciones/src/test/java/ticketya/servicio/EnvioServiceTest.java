package ticketya.servicio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ticketya.RelojDePrueba;
import ticketya.cliente.UsuariosCliente;
import ticketya.envio.Correo;
import ticketya.envio.EnvioDeCorreo;
import ticketya.envio.FalloDefinitivoException;
import ticketya.modelo.EstadoNotificacion;
import ticketya.modelo.Notificacion;
import ticketya.modelo.TipoNotificacion;
import ticketya.repositorio.NotificacionRepository;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Import(RelojDePrueba.Config.class)
class EnvioServiceTest {

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

    @BeforeEach
    void preparar() {
        reloj.poner(RelojDePrueba.INICIO);
    }

    @AfterEach
    void limpiar() {
        repositorio.deleteAll();
    }

    UUID pendiente(String acontecimiento, String usuario) {
        return repositorio.save(Notificacion.nueva(acontecimiento, TipoNotificacion.COMPRA_CONFIRMADA, usuario,
                "compra-1", "corr-1", 1, reloj.instant(), Map.of("evento", "Recital"), reloj.instant())).getId();
    }

    Notificacion leer(UUID id) {
        return repositorio.findById(id).orElseThrow();
    }

    @Test
    void envioExitosoPasaAEnviadaConFecha() {
        UUID id = pendiente("ac-1", "u-1");
        when(usuarios.correoDe("u-1")).thenReturn("ana@example.com");
        reloj.avanzar(Duration.ofSeconds(5));

        assertThat(servicio.procesarPendientes()).isEqualTo(1);

        Notificacion n = leer(id);
        assertThat(n.getEstado()).isEqualTo(EstadoNotificacion.ENVIADA);
        assertThat(n.getEnviadaEn()).isEqualTo(reloj.instant());
        assertThat(n.getIntentos()).isEqualTo(1);
        assertThat(n.getVenceProcesamiento()).isNull();

        ArgumentCaptor<Correo> correo = ArgumentCaptor.forClass(Correo.class);
        verify(envio).enviar(correo.capture());
        assertThat(correo.getValue().destinatario()).isEqualTo("ana@example.com");
        assertThat(correo.getValue().asunto()).isEqualTo("Compra confirmada: Recital");
    }

    @Test
    void destinatarioSinCorreoPasaAErrorFinalSinEnviar() {
        UUID id = pendiente("ac-1", "u-1");
        when(usuarios.correoDe("u-1")).thenThrow(new FalloDefinitivoException("El usuario u-1 no tiene correo registrado"));

        servicio.procesarPendientes();

        Notificacion n = leer(id);
        assertThat(n.getEstado()).isEqualTo(EstadoNotificacion.ERROR_FINAL);
        assertThat(n.getUltimoError()).contains("no tiene correo");
        verify(envio, never()).enviar(any());
    }

    @Test
    void unaNotificacionEnErrorFinalNoSeVuelveAProcesar() {
        pendiente("ac-1", "u-1");
        when(usuarios.correoDe("u-1")).thenThrow(new FalloDefinitivoException("no existe"));
        servicio.procesarPendientes();

        reloj.avanzar(Duration.ofHours(1));
        assertThat(servicio.procesarPendientes()).isZero();
    }

    @Test
    void noTomaLasQueTodaviaNoLlegaronASuProximoIntento() {
        repositorio.save(Notificacion.nueva("ac-1", TipoNotificacion.COMPRA_CONFIRMADA, "u-1", "c", "x", 1,
                reloj.instant(), Map.of(), reloj.instant().plusSeconds(60)));

        assertThat(servicio.procesarPendientes()).isZero();
        verify(envio, never()).enviar(any());
    }

    @Test
    void noTomaLasQueYaEstanProcesando() {
        UUID id = pendiente("ac-1", "u-1");
        repositorio.tomar(id, reloj.instant().plusSeconds(120));

        assertThat(servicio.procesarPendientes()).isZero();
        assertThat(leer(id).getEstado()).isEqualTo(EstadoNotificacion.PROCESANDO);
    }

    @Test
    void procesaVariasEnUnaRonda() {
        UUID a = pendiente("ac-1", "u-1");
        UUID b = pendiente("ac-2", "u-2");
        when(usuarios.correoDe("u-1")).thenReturn("ana@example.com");
        when(usuarios.correoDe("u-2")).thenThrow(new FalloDefinitivoException("no existe"));

        assertThat(servicio.procesarPendientes()).isEqualTo(2);
        assertThat(leer(a).getEstado()).isEqualTo(EstadoNotificacion.ENVIADA);
        assertThat(leer(b).getEstado()).isEqualTo(EstadoNotificacion.ERROR_FINAL);
    }
}
