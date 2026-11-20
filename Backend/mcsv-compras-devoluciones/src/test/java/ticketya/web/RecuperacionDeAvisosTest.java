package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ticketya.PruebaIntegracion;
import ticketya.modelo.AvisoPendiente;
import ticketya.modelo.EstadoAviso;
import ticketya.modelo.TipoAviso;
import ticketya.servicio.AvisoService;
import ticketya.servicio.EnvioDeAvisosService;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Recuperación de avisos que quedaron PROCESANDO (el envío se interrumpió). Máximo: 5 intentos. */
class RecuperacionDeAvisosTest extends PruebaIntegracion {

    private static final String ACONTECIMIENTO = "devolucion-aprobada-d-1";

    @Autowired AvisoService avisoService;
    @Autowired EnvioDeAvisosService envio;
    @Autowired TransactionTemplate transaccion;
    @Autowired JdbcTemplate jdbc;

    private Long nuevoAviso() {
        transaccion.executeWithoutResult(t -> avisoService.registrar(TipoAviso.DEVOLUCION_APROBADA,
                ACONTECIMIENTO, "c1", "d-1", Map.of("evento", "Recital", "monto", "100.00")));
        return elAviso().getId();
    }

    private AvisoPendiente elAviso() {
        return avisos.findByAcontecimientoIdAndTipoAndUsuarioId(ACONTECIMIENTO, TipoAviso.DEVOLUCION_APROBADA, "c1")
                .orElseThrow();
    }

    /** Simula un envío que tomó el aviso y se cortó: queda PROCESANDO con ese vencimiento. */
    private void tomadoYCortado(Long id, String toma, Instant vence) {
        assertThat(avisos.tomar(id, toma, Instant.now(), vence)).isEqualTo(1);
    }

    @Test
    void unAvisoVencidoVuelveAPendienteContandoElIntentoYSeEnviaEnLaProximaRonda() {
        Long id = nuevoAviso();
        tomadoYCortado(id, "toma-cortada", Instant.now().minusSeconds(1));

        assertThat(envio.recuperarVencidos()).isEqualTo(1);

        AvisoPendiente a = elAviso();
        assertThat(a.getEstado()).isEqualTo(EstadoAviso.PENDIENTE);
        assertThat(a.getIntentos()).isEqualTo(1);
        assertThat(a.getUltimoError()).contains("interrumpido");
        assertThat(a.getToma()).isNull();
        assertThat(a.getVenceEn()).isNull();

        assertThat(envio.enviarPendientes()).isEqualTo(1);
        assertThat(elAviso().getEstado()).isEqualTo(EstadoAviso.ENVIADO);
        assertThat(notificaciones.recibidos()).hasSize(1);
    }

    @Test
    void unAvisoQueTodaviaNoVencioNoSeToca() {
        Long id = nuevoAviso();
        tomadoYCortado(id, "toma-en-curso", Instant.now().plusSeconds(120));

        assertThat(envio.recuperarVencidos()).isZero();

        AvisoPendiente a = elAviso();
        assertThat(a.getEstado()).isEqualTo(EstadoAviso.PROCESANDO);
        assertThat(a.getIntentos()).isZero();
        assertThat(a.getToma()).isEqualTo("toma-en-curso");
    }

    @Test
    void unAvisoVencidoQueYaAgotoLosIntentosQuedaEnErrorFinal() {
        Long id = nuevoAviso();
        jdbc.update("update avisos_pendientes set intentos = 4 where id = ?", id);
        tomadoYCortado(id, "toma-cortada", Instant.now().minusSeconds(1));

        assertThat(envio.recuperarVencidos()).isEqualTo(1);

        AvisoPendiente a = elAviso();
        assertThat(a.getEstado()).isEqualTo(EstadoAviso.ERROR_FINAL);
        assertThat(a.getIntentos()).isEqualTo(5);
        assertThat(a.getUltimoError()).contains("interrumpido");
        assertThat(envio.enviarPendientes()).isZero();
    }

    @Test
    void elResultadoDeUnEnvioLentoQueTerminaDespuesDeLaRecuperacionNoPisaElEstado() {
        Long id = nuevoAviso();
        tomadoYCortado(id, "toma-lenta", Instant.now().minusSeconds(1));
        envio.recuperarVencidos();

        // El envío lento termina ahora: su actualización condicional no encuentra su toma
        assertThat(avisos.marcarEnviado(id, "toma-lenta", Instant.now())).isZero();
        assertThat(avisos.reprogramar(id, "toma-lenta", 9, Instant.now(), "tarde")).isZero();
        assertThat(avisos.marcarErrorFinal(id, "toma-lenta", 9, "tarde")).isZero();

        AvisoPendiente a = elAviso();
        assertThat(a.getEstado()).isEqualTo(EstadoAviso.PENDIENTE);
        assertThat(a.getIntentos()).isEqualTo(1);

        // Y tampoco pisa una toma nueva
        envio.enviarPendientes();
        assertThat(avisos.marcarEnviado(id, "toma-lenta", Instant.now())).isZero();
        assertThat(elAviso().getEstado()).isEqualTo(EstadoAviso.ENVIADO);
    }
}
