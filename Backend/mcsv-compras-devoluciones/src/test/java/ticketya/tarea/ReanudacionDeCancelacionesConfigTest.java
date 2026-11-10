package ticketya.tarea;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ticketya.servicio.CancelacionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** La tarea de reanudación se registra por defecto, se desactiva por propiedad y acepta el intervalo en formato simple. */
class ReanudacionDeCancelacionesConfigTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withBean(CancelacionService.class, () -> mock(CancelacionService.class))
            .withUserConfiguration(ProgramacionConfig.class, ReanudacionDeCancelaciones.class);

    @Test
    void porDefectoLaTareaSeRegistra() {
        contexto.run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c).hasSingleBean(ReanudacionDeCancelaciones.class);
        });
    }

    @Test
    void conLaPropiedadEnFalseNoSeRegistraNiSeHabilitaLaProgramacion() {
        contexto.withPropertyValues("compras.cancelacion.reanudacion-activa=false").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c).doesNotHaveBean(ReanudacionDeCancelaciones.class);
            assertThat(c).doesNotHaveBean(ProgramacionConfig.class);
        });
    }

    @Test
    void elIntervaloEnFormatoSimpleSeAceptaYLaTareaLlamaALaReanudacion() {
        contexto.withPropertyValues("compras.cancelacion.intervalo-reanudacion=60s").run(c ->
                assertThat(c).hasNotFailed());

        contexto.withPropertyValues("compras.cancelacion.intervalo-reanudacion=50ms").run(c -> {
            assertThat(c).hasNotFailed();
            verify(c.getBean(CancelacionService.class), timeout(2000).atLeastOnce()).reanudarVencidas();
        });
    }
}
