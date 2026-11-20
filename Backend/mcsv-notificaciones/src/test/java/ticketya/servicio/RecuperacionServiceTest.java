package ticketya.servicio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import ticketya.RelojDePrueba;
import ticketya.modelo.EstadoNotificacion;
import ticketya.modelo.Notificacion;
import ticketya.modelo.TipoNotificacion;
import ticketya.repositorio.NotificacionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "notificaciones.reintentos.maximo-intentos=3",
        "notificaciones.envio.plazo-procesamiento=2m"
})
@ActiveProfiles("test")
@Import(RelojDePrueba.Config.class)
class RecuperacionServiceTest {

    static final Duration PLAZO = Duration.ofMinutes(2);

    @Autowired
    RecuperacionService recuperacion;

    @Autowired
    NotificacionRepository repositorio;

    @Autowired
    RelojDePrueba reloj;

    @BeforeEach
    void preparar() {
        reloj.poner(RelojDePrueba.INICIO);
    }

    @AfterEach
    void limpiar() {
        repositorio.deleteAll();
    }

    /** Guarda una notificación con los intentos fallidos indicados y la deja en PROCESANDO. */
    UUID procesandoConIntentos(String acontecimiento, int intentosPrevios) {
        UUID id = repositorio.save(Notificacion.nueva(acontecimiento, TipoNotificacion.COMPRA_CONFIRMADA, "u-1",
                "c-1", "corr-1", 1, reloj.instant(), Map.of(), reloj.instant())).getId();
        Instant vence = reloj.instant().plus(PLAZO);
        for (int i = 0; i < intentosPrevios; i++) {
            assertThat(repositorio.tomar(id, vence)).isEqualTo(1);
            assertThat(repositorio.guardarReintento(id, vence, reloj.instant(), "fallo previo")).isEqualTo(1);
        }
        assertThat(repositorio.tomar(id, vence)).isEqualTo(1);
        return id;
    }

    Notificacion leer(UUID id) {
        return repositorio.findById(id).orElseThrow();
    }

    @Test
    void antesDelVencimientoNoLaToca() {
        UUID id = procesandoConIntentos("ac-1", 0);
        reloj.avanzar(PLAZO);

        assertThat(recuperacion.recuperarVencidas()).isZero();
        assertThat(leer(id).getEstado()).isEqualTo(EstadoNotificacion.PROCESANDO);
    }

    @Test
    void vencidaVuelveAPendienteContandoUnIntento() {
        UUID id = procesandoConIntentos("ac-1", 0);
        reloj.avanzar(PLAZO.plusSeconds(1));
        Instant ahora = reloj.instant();

        assertThat(recuperacion.recuperarVencidas()).isEqualTo(1);

        Notificacion n = leer(id);
        assertThat(n.getEstado()).isEqualTo(EstadoNotificacion.PENDIENTE);
        assertThat(n.getIntentos()).isEqualTo(1);
        assertThat(n.getProximoIntento()).isEqualTo(ahora);
        assertThat(n.getVenceProcesamiento()).isNull();
        assertThat(n.getUltimoError()).isEqualTo(RecuperacionService.ERROR_VENCIDA);
        // Vuelve a estar disponible para la tarea de envío
        assertThat(repositorio.tomar(id, ahora.plus(PLAZO))).isEqualTo(1);
    }

    @Test
    void vencidaSinIntentosRestantesPasaAErrorFinal() {
        UUID id = procesandoConIntentos("ac-1", 2);
        reloj.avanzar(PLAZO.plusSeconds(1));

        assertThat(recuperacion.recuperarVencidas()).isEqualTo(1);

        Notificacion n = leer(id);
        assertThat(n.getEstado()).isEqualTo(EstadoNotificacion.ERROR_FINAL);
        assertThat(n.getIntentos()).isEqualTo(3);
        assertThat(n.getUltimoError()).isEqualTo(RecuperacionService.ERROR_VENCIDA);
    }

    @Test
    void soloTocaLasProcesandoVencidas() {
        UUID vencida = procesandoConIntentos("ac-1", 1);
        UUID pendiente = repositorio.save(Notificacion.nueva("ac-2", TipoNotificacion.COMPRA_CONFIRMADA, "u-1",
                "c-1", "corr-1", 1, reloj.instant(), Map.of(), reloj.instant())).getId();
        reloj.avanzar(Duration.ofMinutes(1));
        UUID reciente = procesandoConIntentos("ac-3", 0);
        reloj.avanzar(Duration.ofSeconds(61));

        assertThat(recuperacion.recuperarVencidas()).isEqualTo(1);

        assertThat(leer(vencida).getEstado()).isEqualTo(EstadoNotificacion.PENDIENTE);
        assertThat(leer(vencida).getIntentos()).isEqualTo(2);
        assertThat(leer(pendiente).getIntentos()).isZero();
        assertThat(leer(reciente).getEstado()).isEqualTo(EstadoNotificacion.PROCESANDO);
    }

    @Test
    void unaSegundaPasadaNoLaRecuperaDeNuevo() {
        procesandoConIntentos("ac-1", 0);
        reloj.avanzar(PLAZO.plusSeconds(1));

        assertThat(recuperacion.recuperarVencidas()).isEqualTo(1);
        assertThat(recuperacion.recuperarVencidas()).isZero();
    }
}
