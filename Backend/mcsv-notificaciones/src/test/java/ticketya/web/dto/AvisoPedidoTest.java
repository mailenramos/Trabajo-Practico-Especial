package ticketya.web.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ticketya.modelo.TipoNotificacion;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AvisoPedidoTest {

    static ValidatorFactory fabrica;
    static Validator validador;

    @BeforeAll
    static void crear() {
        fabrica = Validation.buildDefaultValidatorFactory();
        validador = fabrica.getValidator();
    }

    @AfterAll
    static void cerrar() {
        fabrica.close();
    }

    static AvisoPedido valido() {
        return new AvisoPedido("ac-1", "COMPRA_CONFIRMADA", 1, Instant.parse("2026-11-17T12:00:00Z"),
                "compra-1", "u-1", "corr-1", Map.of("evento", "Recital"));
    }

    Set<String> camposConError(AvisoPedido aviso) {
        return validador.validate(aviso).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void unAvisoCompletoEsValido() {
        assertThat(validador.validate(valido())).isEmpty();
        assertThat(valido().tipoNotificacion()).isEqualTo(TipoNotificacion.COMPRA_CONFIRMADA);
    }

    @Test
    void faltanCamposObligatorios() {
        AvisoPedido vacio = new AvisoPedido(null, null, null, null, " ", null, "", null);

        assertThat(camposConError(vacio)).containsExactlyInAnyOrder("acontecimientoId", "tipo",
                "versionEsquema", "ocurridoEn", "recursoId", "usuarioId", "correlacionId", "datos");
    }

    @Test
    void tipoDesconocido() {
        AvisoPedido aviso = new AvisoPedido("ac-1", "COMPRA_ANULADA", 1, Instant.now(), "c", "u", "x", Map.of());

        Set<ConstraintViolation<AvisoPedido>> errores = validador.validate(aviso);
        assertThat(errores).singleElement()
                .satisfies(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("tipo");
                    assertThat(v.getMessage()).isEqualTo("tipo de notificación desconocido");
                });
    }

    @Test
    void identificadoresDemasiadoLargos() {
        String largo = "x".repeat(101);
        AvisoPedido aviso = new AvisoPedido(largo, "COMPRA_CONFIRMADA", 1, Instant.now(), largo, largo, largo, Map.of());

        assertThat(camposConError(aviso))
                .containsExactlyInAnyOrder("acontecimientoId", "recursoId", "usuarioId", "correlacionId");
    }
}
