package ticketya.tarea;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ticketya.servicio.EnvioDeAvisosService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** La tarea de envío de avisos se registra por defecto, se desactiva por propiedad y corre sola. */
class EnvioDeAvisosConfigTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withBean(EnvioDeAvisosService.class, () -> mock(EnvioDeAvisosService.class))
            .withUserConfiguration(EnvioDeAvisos.class, EnvioDeAvisos.Programacion.class);

    @Test
    void porDefectoLaTareaSeRegistra() {
        contexto.run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c).hasSingleBean(EnvioDeAvisos.class);
        });
    }

    @Test
    void conLaPropiedadEnFalseNoSeRegistra() {
        contexto.withPropertyValues("compras.avisos.envio-activo=false").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c).doesNotHaveBean(EnvioDeAvisos.class);
            assertThat(c).doesNotHaveBean(EnvioDeAvisos.Programacion.class);
        });
    }

    @Test
    void funcionaAunqueLaReanudacionDeCancelacionesEsteApagada() {
        contexto.withPropertyValues("compras.cancelacion.reanudacion-activa=false",
                "compras.avisos.intervalo-envio=50ms").run(c -> {
            assertThat(c).hasNotFailed();
            EnvioDeAvisosService envio = c.getBean(EnvioDeAvisosService.class);
            verify(envio, timeout(2000).atLeastOnce()).recuperarVencidos();
            verify(envio, timeout(2000).atLeastOnce()).enviarPendientes();
        });
    }
}
