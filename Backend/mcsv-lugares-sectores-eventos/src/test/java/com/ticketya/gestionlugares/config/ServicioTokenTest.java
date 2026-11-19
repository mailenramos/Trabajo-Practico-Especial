package com.ticketya.gestionlugares.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ServicioTokenTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ServicioToken.class);

    @Test
    void dockerFallaConTokenVacioOBlancoInclusoConOtrosPerfiles() {
        for (String profiles : new String[] {"docker", "dev,docker"}) {
            for (String token : new String[] {"", "   "}) {
                contextRunner.withPropertyValues("spring.profiles.active=" + profiles, "servicio.token=" + token)
                        .run(context -> assertThat(context.getStartupFailure())
                                .hasRootCauseMessage("SERVICIO_TOKEN no configurada"));
            }
        }
    }

    @Test
    void sinTokenFueraDeDockerArrancaYAdvierte(CapturedOutput output) {
        contextRunner.withPropertyValues("spring.profiles.active=dev", "servicio.token=   ")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).contains("SERVICIO_TOKEN no configurada");
    }

    @Test
    void tokenConfiguradoNoSeRegistra(CapturedOutput output) {
        contextRunner.withPropertyValues("spring.profiles.active=docker", "servicio.token=valor-secreto-prueba")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertEquals("valor-secreto-prueba", context.getBean(ServicioToken.class).valor());
                });
        assertThat(output).doesNotContain("valor-secreto-prueba");
    }
}
