package com.ticketya.gestionlugares.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.LugarRepository;

class DevSampleDataInitializerProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DevSampleDataInitializer.class)
            .withBean(LugarRepository.class, () -> mock(LugarRepository.class))
            .withBean(EventoRepository.class, () -> mock(EventoRepository.class));

    @Test
    void elInicializadorSoloSeRegistraConElPerfilDev() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(DevSampleDataInitializer.class));

        contextRunner.withPropertyValues("spring.profiles.active=dev").run(context ->
                assertThat(context).hasSingleBean(DevSampleDataInitializer.class));
    }
}
