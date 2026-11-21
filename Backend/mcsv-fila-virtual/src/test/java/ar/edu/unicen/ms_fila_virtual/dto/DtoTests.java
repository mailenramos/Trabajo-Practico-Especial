package ar.edu.unicen.ms_fila_virtual.dto;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import ar.edu.unicen.ms_fila_virtual.dto.ErrorResponse.CampoError;
import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoFila;
import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoTurno;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class DtoTests {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final Instant INSTANTE = Instant.parse("2026-11-17T15:00:00Z");

    @AfterAll
    static void cerrarValidator() { FACTORY.close(); }

    @Test
    void configuracionExigeAmbosEnterosPositivos() {
        assertTrue(VALIDATOR.validate(new ConfiguracionFilaRequest(10, 5)).isEmpty());
        assertEquals(Set.of("cupo", "duracionMinutos"), campos(new ConfiguracionFilaRequest(null, null)));
        assertEquals(Set.of("cupo", "duracionMinutos"), campos(new ConfiguracionFilaRequest(0, -1)));
        assertTrue(VALIDATOR.validate(new ConfiguracionFilaRequest(Integer.MAX_VALUE, Integer.MAX_VALUE)).isEmpty());
    }

    @Test
    void estadoEsObligatorioYLaDeserializacionRechazaValoresYCamposInvalidos() {
        assertEquals(Set.of("estado"), campos(new EstadoFilaRequest(null)));
        assertTrue(VALIDATOR.validate(new EstadoFilaRequest(EstadoFila.PAUSADA)).isEmpty());
        assertEquals(EstadoFila.ACTIVA, JSON.readValue("{\"estado\":\"ACTIVA\"}", EstadoFilaRequest.class).estado());
        assertThrows(RuntimeException.class, () -> JSON.readValue("{\"estado\":\"OTRO\"}", EstadoFilaRequest.class));
        assertThrows(RuntimeException.class, () -> JSON.readValue("{\"estado\":\"ACTIVA\",\"otro\":1}", EstadoFilaRequest.class));
        assertThrows(RuntimeException.class, () -> JSON.readValue("{\"cupo\":2147483648,\"duracionMinutos\":5}", ConfiguracionFilaRequest.class));
    }

    @Test
    void consumoValidaLimitesPorPuntosDeCodigoSinAlterarIdentificadores() {
        assertEquals(Set.of("usuarioId", "solicitudId", "huellaSolicitud"), campos(new ConsumirTurnoRequest(null, "", "")));
        String emoji = "😀";
        assertTrue(VALIDATOR.validate(new ConsumirTurnoRequest(emoji.repeat(255), " usuario ", "v1:huella")).isEmpty());
        assertEquals(Set.of("usuarioId", "solicitudId", "huellaSolicitud"),
                campos(new ConsumirTurnoRequest(emoji.repeat(256), "a".repeat(256), "b".repeat(256))));
        ConsumirTurnoRequest request = JSON.readValue("{\"usuarioId\":\" usuario \",\"solicitudId\":\"sol \",\"huellaSolicitud\":\"v1:h\"}", ConsumirTurnoRequest.class);
        assertEquals(" usuario ", request.usuarioId());
        assertEquals("sol ", request.solicitudId());
    }

    @Test
    void serializaTurnosConFechasUtcYOmitiendoNulos() {
        String esperando = JSON.writeValueAsString(new TurnoResponse(12L, "evento", EstadoTurno.ESPERANDO, 2L, INSTANTE, null, null));
        assertEquals(JSON.readTree("{\"turnoId\":12,\"eventoId\":\"evento\",\"estado\":\"ESPERANDO\",\"posicion\":2,\"ingresadoEn\":\"2026-11-17T15:00:00Z\"}"), JSON.readTree(esperando));
        String habilitado = JSON.writeValueAsString(new TurnoResponse(12L, "evento", EstadoTurno.HABILITADO, null, INSTANTE, INSTANTE, INSTANTE.plusSeconds(300)));
        assertEquals(JSON.readTree("{\"turnoId\":12,\"eventoId\":\"evento\",\"estado\":\"HABILITADO\",\"ingresadoEn\":\"2026-11-17T15:00:00Z\",\"habilitadoEn\":\"2026-11-17T15:00:00Z\",\"expiraEn\":\"2026-11-17T15:05:00Z\"}"), JSON.readTree(habilitado));
    }

    @Test
    void serializaFilaYConsumoSinAgregarCampos() {
        var fila = new FilaEventoResponse("evento", EstadoFila.ACTIVA, false, 10, 5, 23, 10);
        assertEquals(JSON.readTree("{\"eventoId\":\"evento\",\"estado\":\"ACTIVA\",\"cancelado\":false,\"cupo\":10,\"duracionMinutos\":5,\"cantidadEsperando\":23,\"cantidadHabilitados\":10}"), JSON.readTree(JSON.writeValueAsString(fila)));
        var consumo = new ConsumoTurnoResponse(12L, "evento", "sol", EstadoTurno.CONSUMIDO, INSTANTE, false);
        assertEquals(JSON.readTree("{\"turnoId\":12,\"eventoId\":\"evento\",\"solicitudId\":\"sol\",\"estado\":\"CONSUMIDO\",\"expiraEn\":\"2026-11-17T15:00:00Z\",\"autorizacionVigente\":false}"), JSON.readTree(JSON.writeValueAsString(consumo)));
    }

    @Test
    void serializaErroresConFormatoDeMs2YCamposOpcionales() {
        var sinCampos = new ErrorResponse(INSTANTE, 409, "Conflict", "Conflicto", null);
        assertEquals(JSON.readTree("{\"timestamp\":\"2026-11-17T15:00:00Z\",\"status\":409,\"error\":\"Conflict\",\"mensaje\":\"Conflicto\"}"), JSON.readTree(JSON.writeValueAsString(sinCampos)));
        var conCampos = new ErrorResponse(INSTANTE, 400, "Bad Request", "Inválido", List.of(new CampoError("cupo", "es obligatorio")));
        assertEquals(JSON.readTree("{\"timestamp\":\"2026-11-17T15:00:00Z\",\"status\":400,\"error\":\"Bad Request\",\"mensaje\":\"Inválido\",\"campos\":[{\"campo\":\"cupo\",\"mensaje\":\"es obligatorio\"}]}"), JSON.readTree(JSON.writeValueAsString(conCampos)));
    }

    private static <T> Set<String> campos(T request) {
        return VALIDATOR.validate(request).stream().map(ConstraintViolation::getPropertyPath)
                .map(Object::toString).collect(Collectors.toSet());
    }
}
