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
import ticketya.envio.FalloDefinitivoException;
import ticketya.envio.FalloTemporalException;
import ticketya.modelo.EstadoNotificacion;
import ticketya.modelo.Notificacion;
import ticketya.modelo.TipoNotificacion;
import ticketya.repositorio.NotificacionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * El resultado de un envío se guarda con una actualización condicional (PROCESANDO y la misma
 * toma): nunca pisa lo que hizo la recuperación ni una toma posterior.
 */
@SpringBootTest(properties = {
        "notificaciones.reintentos.maximo-intentos=3",
        "notificaciones.envio.plazo-procesamiento=2m"
})
@ActiveProfiles("test")
@Import(RelojDePrueba.Config.class)
class GuardadoCondicionalTest {

    static final Duration PLAZO = Duration.ofMinutes(2);

    @Autowired
    EnvioService envioService;

    @Autowired
    RecuperacionService recuperacion;

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
        id = repositorio.save(Notificacion.nueva("ac-1", TipoNotificacion.COMPRA_CONFIRMADA, "u-1", "c-1", "corr-1",
                1, reloj.instant(), Map.of("evento", "Recital"), reloj.instant())).getId();
        when(usuarios.correoDe("u-1")).thenReturn("ana@example.com");
    }

    @AfterEach
    void limpiar() {
        repositorio.deleteAll();
    }

    Notificacion leer() {
        return repositorio.findById(id).orElseThrow();
    }

    /** Toma la notificación y devuelve la entidad leída después de la toma, como hace la tarea. */
    Notificacion tomada() {
        assertThat(repositorio.tomar(id, reloj.instant().plus(PLAZO))).isEqualTo(1);
        return leer();
    }

    /** La recuperación la libera (PENDIENTE, intentos 1) mientras el envío de {@code enCurso} sigue. */
    void laRecuperaLaOtraTarea() {
        reloj.avanzar(PLAZO.plusSeconds(1));
        assertThat(recuperacion.recuperarVencidas()).isEqualTo(1);
    }

    void assertQuedoComoLaDejoLaRecuperacion() {
        Notificacion n = leer();
        assertThat(n.getEstado()).isEqualTo(EstadoNotificacion.PENDIENTE);
        assertThat(n.getIntentos()).isEqualTo(1);
        assertThat(n.getUltimoError()).isEqualTo(RecuperacionService.ERROR_VENCIDA);
        assertThat(n.getEnviadaEn()).isNull();
    }

    // (a) Si la notificación ya no está en PROCESANDO, el resultado no modifica el registro,
    //     en los cuatro caminos

    @Test
    void envioExitosoTardioNoPisaLaRecuperacion() {
        Notificacion enCurso = tomada();
        laRecuperaLaOtraTarea();

        envioService.procesar(enCurso);

        assertQuedoComoLaDejoLaRecuperacion();
    }

    @Test
    void falloTemporalTardioNoPisaLaRecuperacion() {
        Notificacion enCurso = tomada();
        laRecuperaLaOtraTarea();
        doThrow(new FalloTemporalException("SMTP no disponible")).when(envio).enviar(any());

        envioService.procesar(enCurso);

        assertQuedoComoLaDejoLaRecuperacion();
    }

    @Test
    void errorDefinitivoTardioNoPisaLaRecuperacion() {
        Notificacion enCurso = tomada();
        laRecuperaLaOtraTarea();
        when(usuarios.correoDe("u-1")).thenThrow(new FalloDefinitivoException("sin correo"));

        envioService.procesar(enCurso);

        assertQuedoComoLaDejoLaRecuperacion();
    }

    @Test
    void intentosAgotadosTardioNoPisaLaRecuperacion() {
        // Dos fallos previos: el próximo fallo agotaría los 3 intentos
        Instant vence = reloj.instant().plus(PLAZO);
        for (int i = 0; i < 2; i++) {
            repositorio.tomar(id, vence);
            repositorio.guardarReintento(id, vence, reloj.instant(), "fallo previo");
        }
        Notificacion enCurso = tomada();
        reloj.avanzar(PLAZO.plusSeconds(1));
        recuperacion.recuperarVencidas();
        assertThat(leer().getEstado()).isEqualTo(EstadoNotificacion.ERROR_FINAL);
        assertThat(leer().getIntentos()).isEqualTo(3);
        doThrow(new FalloTemporalException("SMTP no disponible")).when(envio).enviar(any());

        envioService.procesar(enCurso);

        Notificacion n = leer();
        assertThat(n.getEstado()).isEqualTo(EstadoNotificacion.ERROR_FINAL);
        assertThat(n.getIntentos()).isEqualTo(3);
        assertThat(n.getUltimoError()).isEqualTo(RecuperacionService.ERROR_VENCIDA);
    }

    @Test
    void resultadoDeUnaTomaViejaNoPisaUnaTomaNueva() {
        Notificacion tomaVieja = tomada();
        laRecuperaLaOtraTarea();
        Notificacion tomaNueva = tomada();

        envioService.procesar(tomaVieja);

        Notificacion n = leer();
        assertThat(n.getEstado()).isEqualTo(EstadoNotificacion.PROCESANDO);
        assertThat(n.getVenceProcesamiento()).isEqualTo(tomaNueva.getVenceProcesamiento());
        assertThat(n.getIntentos()).isEqualTo(1);
    }

    // (b) El camino normal sigue funcionando

    @Test
    void caminoNormalGuardaElResultado() {
        envioService.procesar(tomada());

        Notificacion n = leer();
        assertThat(n.getEstado()).isEqualTo(EstadoNotificacion.ENVIADA);
        assertThat(n.getIntentos()).isEqualTo(1);
        assertThat(n.getVenceProcesamiento()).isNull();
        assertThat(n.getEnviadaEn()).isEqualTo(reloj.instant());
    }

    @Test
    void caminoNormalConFalloTemporalYDefinitivo() {
        doThrow(new FalloTemporalException("SMTP no disponible")).when(envio).enviar(any());
        envioService.procesar(tomada());
        assertThat(leer().getEstado()).isEqualTo(EstadoNotificacion.PENDIENTE);
        assertThat(leer().getIntentos()).isEqualTo(1);

        when(usuarios.correoDe("u-1")).thenThrow(new FalloDefinitivoException("sin correo"));
        envioService.procesar(tomada());
        assertThat(leer().getEstado()).isEqualTo(EstadoNotificacion.ERROR_FINAL);
        assertThat(leer().getIntentos()).isEqualTo(2);
        assertThat(leer().getUltimoError()).isEqualTo("sin correo");
    }

    // (c) Concurrencia real entre un envío lento, la recuperación y una nueva toma

    @Test
    void envioLentoRecuperacionYNuevaTomaNoPierdenNingunaTransicion() throws Exception {
        CountDownLatch primerEnvioEmpezo = new CountDownLatch(1);
        CountDownLatch soltarPrimerEnvio = new CountDownLatch(1);
        CountDownLatch segundoEnvioEmpezo = new CountDownLatch(1);
        CountDownLatch soltarSegundoEnvio = new CountDownLatch(1);
        AtomicInteger envios = new AtomicInteger();
        doAnswer(invocacion -> {
            if (envios.incrementAndGet() == 1) {
                primerEnvioEmpezo.countDown();
                assertThat(soltarPrimerEnvio.await(10, TimeUnit.SECONDS)).isTrue();
            } else {
                segundoEnvioEmpezo.countDown();
                assertThat(soltarSegundoEnvio.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(envio).enviar(any());

        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            // Instancia 1 toma la notificación y se queda trabada en SMTP
            Future<Integer> instancia1 = hilos.submit(envioService::procesarPendientes);
            assertThat(primerEnvioEmpezo.await(10, TimeUnit.SECONDS)).isTrue();

            // Vence y la recuperación la devuelve a PENDIENTE contando el intento
            laRecuperaLaOtraTarea();
            assertQuedoComoLaDejoLaRecuperacion();

            // Instancia 2 la vuelve a tomar y también se queda en SMTP
            Future<Integer> instancia2 = hilos.submit(envioService::procesarPendientes);
            assertThat(segundoEnvioEmpezo.await(10, TimeUnit.SECONDS)).isTrue();
            Instant venceToma2 = leer().getVenceProcesamiento();

            // Termina el envío lento de la instancia 1: no debe pisar la toma de la instancia 2
            soltarPrimerEnvio.countDown();
            assertThat(instancia1.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            Notificacion trasLaInstancia1 = leer();
            assertThat(trasLaInstancia1.getEstado()).isEqualTo(EstadoNotificacion.PROCESANDO);
            assertThat(trasLaInstancia1.getVenceProcesamiento()).isEqualTo(venceToma2);
            assertThat(trasLaInstancia1.getIntentos()).isEqualTo(1);

            // Termina la instancia 2: su resultado sí se guarda, sobre el intento contado por la recuperación
            soltarSegundoEnvio.countDown();
            assertThat(instancia2.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            hilos.shutdownNow();
        }

        Notificacion n = leer();
        assertThat(n.getEstado()).isEqualTo(EstadoNotificacion.ENVIADA);
        assertThat(n.getIntentos()).isEqualTo(2);
        assertThat(n.getEnviadaEn()).isNotNull();
        assertThat(n.getUltimoError()).isNull();
    }
}
