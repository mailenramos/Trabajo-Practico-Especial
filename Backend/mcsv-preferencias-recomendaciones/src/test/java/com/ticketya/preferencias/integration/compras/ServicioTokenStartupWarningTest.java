package com.ticketya.preferencias.integration.compras;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ServicioTokenStartupWarningTest {

    @Test
    void advierteAlArrancarSiServicioTokenNoEstaDefinido(CapturedOutput output) {
        ComprasProperties properties = new ComprasProperties();
        properties.setServicioToken("");
        ServicioTokenStartupWarning warning = new ServicioTokenStartupWarning(properties);

        assertDoesNotThrow(() -> warning.run(new DefaultApplicationArguments()));

        assertTrue(output.getOut().contains("WARN"));
        assertTrue(output.getOut().contains("SERVICIO_TOKEN"));
    }

    @Test
    void noAdvierteSiServicioTokenEstaConfigurado(CapturedOutput output) throws Exception {
        ComprasProperties properties = new ComprasProperties();
        properties.setServicioToken("token-de-prueba");

        new ServicioTokenStartupWarning(properties).run(new DefaultApplicationArguments());

        assertFalse(output.getOut().contains("SERVICIO_TOKEN no está definida"));
    }
}
