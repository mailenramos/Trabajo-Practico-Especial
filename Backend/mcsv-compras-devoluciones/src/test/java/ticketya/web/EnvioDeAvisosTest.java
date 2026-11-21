package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ticketya.PruebaIntegracion;
import ticketya.cliente.NotificacionesCliente.Aviso;
import ticketya.modelo.AvisoPendiente;
import ticketya.modelo.EstadoAviso;
import ticketya.modelo.TipoAviso;
import ticketya.servicio.AvisoService;
import ticketya.servicio.EnvioDeAvisosService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tarea de envío del outbox: ENVIADO con 2xx, reintentos con espera creciente, ERROR_FINAL al
 * agotar los intentos o ante un rechazo, y toma condicional entre ejecuciones simultáneas.
 * Valores por defecto: 5 intentos, espera base 30 s.
 */
class EnvioDeAvisosTest extends PruebaIntegracion {

    @Autowired AvisoService avisoService;
    @Autowired EnvioDeAvisosService envio;
    @Autowired TransactionTemplate transaccion;
    @Autowired JdbcTemplate jdbc;

    private AvisoPendiente nuevoAviso(String usuarioId) {
        transaccion.executeWithoutResult(t -> avisoService.registrar(TipoAviso.DEVOLUCION_RECHAZADA,
                "devolucion-rechazada-d-1", usuarioId, "d-1", Map.of("evento", "Recital", "motivo", "no")));
        return elAviso(usuarioId);
    }

    private AvisoPendiente elAviso(String usuarioId) {
        return avisos.findByAcontecimientoIdAndTipoAndUsuarioId("devolucion-rechazada-d-1",
                TipoAviso.DEVOLUCION_RECHAZADA, usuarioId).orElseThrow();
    }

    /** Adelanta el próximo intento para no esperar la espera real. */
    private void adelantarProximoIntento() {
        jdbc.update("update avisos_pendientes set proximo_intento = '2000-01-01 00:00:00'");
    }

    @Test
    void conRespuesta2xxElAvisoQuedaEnviadoConElContratoDeNotificaciones() {
        AvisoPendiente pendiente = nuevoAviso("c1");

        assertThat(envio.enviarPendientes()).isEqualTo(1);

        AvisoPendiente a = elAviso("c1");
        assertThat(a.getEstado()).isEqualTo(EstadoAviso.ENVIADO);
        assertThat(a.getEnviadoEn()).isNotNull();
        assertThat(a.getToma()).isNull();
        assertThat(notificaciones.recibidos()).singleElement().satisfies(enviado -> {
            assertThat(enviado.acontecimientoId()).isEqualTo("devolucion-rechazada-d-1");
            assertThat(enviado.tipo()).isEqualTo("DEVOLUCION_RECHAZADA");
            assertThat(enviado.versionEsquema()).isEqualTo(1);
            assertThat(enviado.usuarioId()).isEqualTo("c1");
            assertThat(enviado.recursoId()).isEqualTo("d-1");
            assertThat(enviado.correlacionId()).isEqualTo(pendiente.getCorrelacionId());
            assertThat(enviado.ocurridoEn()).isEqualTo(pendiente.getCreadoEn());
            assertThat(enviado.datos().get("motivo").asString()).isEqualTo("no");
        });

        // Un aviso enviado no se vuelve a enviar
        adelantarProximoIntento();
        assertThat(envio.enviarPendientes()).isZero();
        assertThat(notificaciones.recibidos()).hasSize(1);
    }

    @Test
    void unaFallaTemporalLoReprogramaConEsperaCrecienteYElMismoAcontecimiento() {
        nuevoAviso("c1");
        notificaciones.setCaido(true);

        List<Duration> esperas = new ArrayList<>();
        for (int intento = 1; intento <= 3; intento++) {
            Instant antes = Instant.now();
            envio.enviarPendientes();
            AvisoPendiente a = elAviso("c1");
            assertThat(a.getEstado()).isEqualTo(EstadoAviso.PENDIENTE);
            assertThat(a.getIntentos()).isEqualTo(intento);
            assertThat(a.getUltimoError()).contains("no responde");
            esperas.add(Duration.between(antes, a.getProximoIntento()));
            // Antes de la espera no se reintenta
            assertThat(envio.enviarPendientes()).isZero();
            assertThat(elAviso("c1").getIntentos()).isEqualTo(intento);
            adelantarProximoIntento();
        }
        assertThat(esperas.get(0)).isBetween(Duration.ofSeconds(28), Duration.ofSeconds(35));
        assertThat(esperas.get(1)).isBetween(Duration.ofSeconds(58), Duration.ofSeconds(65));
        assertThat(esperas.get(2)).isBetween(Duration.ofSeconds(118), Duration.ofSeconds(125));

        notificaciones.setCaido(false);
        assertThat(envio.enviarPendientes()).isEqualTo(1);
        assertThat(elAviso("c1").getEstado()).isEqualTo(EstadoAviso.ENVIADO);
        assertThat(elAviso("c1").getUltimoError()).isNull();
        assertThat(notificaciones.recibidos()).extracting(Aviso::acontecimientoId)
                .containsExactly("devolucion-rechazada-d-1");
    }

    @Test
    void alAgotarLosIntentosQuedaEnErrorFinalConElUltimoError() {
        nuevoAviso("c1");
        notificaciones.setCaido(true);

        for (int intento = 1; intento <= 5; intento++) {
            envio.enviarPendientes();
            adelantarProximoIntento();
        }

        AvisoPendiente a = elAviso("c1");
        assertThat(a.getEstado()).isEqualTo(EstadoAviso.ERROR_FINAL);
        assertThat(a.getIntentos()).isEqualTo(5);
        assertThat(a.getUltimoError()).contains("no responde");

        notificaciones.setCaido(false);
        assertThat(envio.enviarPendientes()).isZero();
        assertThat(notificaciones.recibidos()).isEmpty();
    }

    @Test
    void unRechazoDefinitivoNoSeReintenta() {
        nuevoAviso("c1");
        notificaciones.setRechazar(true);

        envio.enviarPendientes();

        AvisoPendiente a = elAviso("c1");
        assertThat(a.getEstado()).isEqualTo(EstadoAviso.ERROR_FINAL);
        assertThat(a.getIntentos()).isEqualTo(1);
        assertThat(a.getUltimoError()).contains("400");
    }

    @Test
    void dosEjecucionesSimultaneasEnvianCadaAvisoUnaSolaVez() throws Exception {
        for (int i = 0; i < 20; i++) {
            nuevoAviso("c" + i);
        }
        int hilos = 4;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Integer>> resultados = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            resultados.add(pool.submit(() -> {
                largada.await();
                return envio.enviarPendientes();
            }));
        }
        largada.countDown();
        int enviados = 0;
        for (Future<Integer> f : resultados) {
            enviados += f.get();
        }
        pool.shutdown();

        assertThat(enviados).isEqualTo(20);
        assertThat(notificaciones.recibidos()).hasSize(20);
        assertThat(notificaciones.recibidos()).extracting(Aviso::usuarioId).doesNotHaveDuplicates();
        assertThat(avisos.findAll()).allMatch(a -> a.getEstado() == EstadoAviso.ENVIADO);
    }
}
