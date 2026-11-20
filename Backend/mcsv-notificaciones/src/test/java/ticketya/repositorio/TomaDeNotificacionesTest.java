package ticketya.repositorio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import ticketya.modelo.EstadoNotificacion;
import ticketya.modelo.Notificacion;
import ticketya.modelo.TipoNotificacion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Sin transacción de test envolvente: cada toma se confirma por separado, como en producción. */
@SpringBootTest
@ActiveProfiles("test")
class TomaDeNotificacionesTest {

    static final Instant AHORA = Instant.parse("2026-11-17T12:00:00Z");
    static final Instant VENCE = AHORA.plusSeconds(120);

    @Autowired
    NotificacionRepository repositorio;

    @AfterEach
    void limpiar() {
        repositorio.deleteAll();
    }

    Notificacion guardar(String acontecimiento, Instant proximoIntento) {
        return repositorio.save(Notificacion.nueva(acontecimiento, TipoNotificacion.COMPRA_CONFIRMADA, "u-1",
                "compra-1", "corr-1", 1, AHORA, Map.of(), proximoIntento));
    }

    @Test
    void tomaUnaPendienteYNoLaVuelveATomar() {
        UUID id = guardar("ac-1", AHORA).getId();

        assertThat(repositorio.tomar(id, VENCE)).isEqualTo(1);
        assertThat(repositorio.tomar(id, VENCE)).isZero();

        Notificacion n = repositorio.findById(id).orElseThrow();
        assertThat(n.getEstado()).isEqualTo(EstadoNotificacion.PROCESANDO);
        assertThat(n.getVenceProcesamiento()).isEqualTo(VENCE);
    }

    @Test
    void listaSoloLasPendientesCuyoProximoIntentoLlego() {
        UUID atrasada = guardar("ac-1", AHORA.minusSeconds(60)).getId();
        UUID enHora = guardar("ac-2", AHORA).getId();
        guardar("ac-3", AHORA.plusSeconds(60));
        UUID tomada = guardar("ac-4", AHORA.minusSeconds(120)).getId();
        repositorio.tomar(tomada, VENCE);

        assertThat(repositorio.idsListosParaEnviar(AHORA, PageRequest.of(0, 10)))
                .containsExactly(atrasada, enHora);
        assertThat(repositorio.idsListosParaEnviar(AHORA, PageRequest.of(0, 1))).containsExactly(atrasada);
    }

    @Test
    void conVariasInstanciasALaVezCadaNotificacionLaTomaUnaSola() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ids.add(guardar("ac-" + i, AHORA).getId());
        }
        int instancias = 4;
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService hilos = Executors.newFixedThreadPool(instancias);
        try {
            List<Future<Integer>> tomadasPorInstancia = new ArrayList<>();
            for (int i = 0; i < instancias; i++) {
                Callable<Integer> instancia = () -> {
                    largada.await();
                    int tomadas = 0;
                    for (UUID id : ids) {
                        tomadas += repositorio.tomar(id, VENCE);
                    }
                    return tomadas;
                };
                tomadasPorInstancia.add(hilos.submit(instancia));
            }
            largada.countDown();

            int total = 0;
            for (Future<Integer> f : tomadasPorInstancia) {
                total += f.get();
            }
            assertThat(total).isEqualTo(ids.size());
        } finally {
            hilos.shutdownNow();
        }
        assertThat(repositorio.findAll()).allMatch(n -> n.getEstado() == EstadoNotificacion.PROCESANDO);
    }
}
