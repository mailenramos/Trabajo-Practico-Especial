package com.ticketya.gestionlugares.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;

@ExtendWith(OutputCaptureExtension.class)
class CircuitBreakerConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(CircuitBreakerAutoConfiguration.class));

    @Test
    void aplicaDefaultsEnAmbasInstancias() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            CircuitBreakerRegistry registry = result.getBean(CircuitBreakerRegistry.class);
            for (String nombre : new String[] {"reservas", "usuarios"}) {
                CircuitBreakerConfig config = registry.circuitBreaker(nombre).getCircuitBreakerConfig();
                assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
                assertThat(config.getSlidingWindowSize()).isEqualTo(10);
                assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
                assertThat(config.getFailureRateThreshold()).isEqualTo(50);
                assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(Duration.ofSeconds(30).toMillis());
                assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(1);
            }
        });
    }

    @Test
    void permiteCambiarUnaInstanciaSinAfectarLaOtra() {
        context.withPropertyValues("resilience4j.circuitbreaker.instances.reservas.sliding-window-size=4",
                "resilience4j.circuitbreaker.instances.reservas.minimum-number-of-calls=4")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    CircuitBreakerRegistry registry = result.getBean(CircuitBreakerRegistry.class);
                    CircuitBreakerConfig reservas = registry.circuitBreaker("reservas").getCircuitBreakerConfig();
                    CircuitBreakerConfig usuarios = registry.circuitBreaker("usuarios").getCircuitBreakerConfig();
                    assertThat(reservas.getSlidingWindowSize()).isEqualTo(4);
                    assertThat(reservas.getMinimumNumberOfCalls()).isEqualTo(4);
                    assertThat(usuarios.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
                    assertThat(usuarios.getSlidingWindowSize()).isEqualTo(10);
                    assertThat(usuarios.getMinimumNumberOfCalls()).isEqualTo(10);
                    assertThat(usuarios.getFailureRateThreshold()).isEqualTo(50);
                    assertThat(usuarios.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(Duration.ofSeconds(30).toMillis());
                    assertThat(usuarios.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(1);
                });
    }

    @Test
    void registraTransicionesDeAmbosCircuitosEnWarn(CapturedOutput output) {
        var registry = CircuitBreakerRegistry.ofDefaults();
        new CircuitBreakerTransiciones(registry);
        registry.circuitBreaker("reservas").transitionToOpenState();
        registry.circuitBreaker("usuarios").transitionToOpenState();
        registry.circuitBreaker("usuarios").transitionToHalfOpenState();

        assertThat(output.getAll()).contains("WARN", "Circuito reservas cambió de CLOSED a OPEN",
                "Circuito usuarios cambió de CLOSED a OPEN", "Circuito usuarios cambió de OPEN a HALF_OPEN");
    }
}
