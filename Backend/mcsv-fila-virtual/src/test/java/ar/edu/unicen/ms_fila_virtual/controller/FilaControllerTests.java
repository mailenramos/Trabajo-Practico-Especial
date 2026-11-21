package ar.edu.unicen.ms_fila_virtual.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.edu.unicen.ms_fila_virtual.config.ClockConfiguration;
import ar.edu.unicen.ms_fila_virtual.dto.ConsumoTurnoResponse;
import ar.edu.unicen.ms_fila_virtual.dto.FilaEventoResponse;
import ar.edu.unicen.ms_fila_virtual.dto.TurnoResponse;
import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoFila;
import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoTurno;
import ar.edu.unicen.ms_fila_virtual.service.FilaConflictException;
import ar.edu.unicen.ms_fila_virtual.service.FilaNoEncontradaException;
import ar.edu.unicen.ms_fila_virtual.service.FilaVirtualService;
import ar.edu.unicen.ms_fila_virtual.service.FilaCoordinacionService;
import ar.edu.unicen.ms_fila_virtual.service.Ms1NoDisponibleException;
import ar.edu.unicen.ms_fila_virtual.service.ResultadoIngreso;

@WebMvcTest(controllers = {FilaController.class, FilaInternaController.class}, properties = "SERVICIO_TOKEN=token-prueba")
@Import(ClockConfiguration.class)
class FilaControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean FilaVirtualService servicio;
    @MockitoBean FilaCoordinacionService coordinacion;

    private final Instant ahora = Instant.parse("2026-11-17T15:00:00Z");
    private TurnoResponse turno() { return new TurnoResponse(1L, "e", EstadoTurno.ESPERANDO, 1L, ahora, null, null); }

    @Test
    void correlacionSeDevuelveEnErroresYNoSeFiltraEntreSolicitudes() throws Exception {
        mvc.perform(get("/filas/eventos/e/mi-turno").header("X-Correlation-Id", "corr-primera"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("X-Correlation-Id", "corr-primera"))
                .andExpect(jsonPath("$.status").value(401));
        org.junit.jupiter.api.Assertions.assertNull(org.slf4j.MDC.get(CorrelacionFilter.CORRELACION));
        var sinId = mvc.perform(get("/filas/eventos/e/mi-turno"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getHeader("X-Correlation-Id");
        org.junit.jupiter.api.Assertions.assertNotNull(java.util.UUID.fromString(sinId));
        org.junit.jupiter.api.Assertions.assertNotEquals("corr-primera", sinId);
        var invalido = mvc.perform(get("/filas/eventos/e/mi-turno").header("X-Correlation-Id", "no válido"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getHeader("X-Correlation-Id");
        org.junit.jupiter.api.Assertions.assertNotNull(java.util.UUID.fromString(invalido));
        org.junit.jupiter.api.Assertions.assertNotEquals(sinId, invalido);
        org.junit.jupiter.api.Assertions.assertNull(org.slf4j.MDC.get(CorrelacionFilter.CORRELACION));
    }

    @Test
    void ingresoNuevoYRepetidoUsanIdentidadPropagada() throws Exception {
        when(servicio.ingresar("e", "cliente")).thenReturn(new ResultadoIngreso(true, turno()), new ResultadoIngreso(false, turno()));
        mvc.perform(post("/filas/eventos/e/ingresos").header("X-User-Id", "cliente").header("X-User-Roles", "ORGANIZADOR, CLIENTE"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("ESPERANDO"))
                .andExpect(jsonPath("$.posicion").value(1)).andExpect(jsonPath("$.expiraEn").doesNotExist());
        mvc.perform(post("/filas/eventos/e/ingresos").header("X-User-Id", "cliente").header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isOk());
        verify(servicio, times(2)).ingresar("e", "cliente");
    }

    @Test
    void miTurnoSoloUsaUsuarioPropioYTraduceAusenciaYConflicto() throws Exception {
        when(servicio.consultarTurno("e", "u")).thenReturn(turno()).thenThrow(new FilaNoEncontradaException("No existe turno."));
        mvc.perform(get("/filas/eventos/e/mi-turno").header("X-User-Id", "u").header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turnoId").value(1));
        mvc.perform(get("/filas/eventos/e/mi-turno").header("X-User-Id", "u").header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.mensaje").value("No existe turno."));
        when(servicio.ingresar("e", "u")).thenThrow(new FilaConflictException("Fila pausada."));
        mvc.perform(post("/filas/eventos/e/ingresos").header("X-User-Id", "u").header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    void identidadYRolesSonObligatoriosYNoSeAceptanAliases() throws Exception {
        mvc.perform(get("/filas/eventos/e/mi-turno")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/filas/eventos/e/mi-turno").header("X-User-Id", "u").header("X-User-Role", "CLIENTE"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        mvc.perform(get("/filas/eventos/e/mi-turno").header("X-User-Id", "u").header("X-User-Roles", " "))
                .andExpect(status().isForbidden());
        mvc.perform(get("/filas/eventos/e/mi-turno").header("X-User-Id", " ").header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/filas/eventos/e/mi-turno").header("X-User-Id", "u".repeat(256)).header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/filas/eventos/e/mi-turno").header("X-User-Id", "u").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/filas/eventos/e/mi-turno").header("X-User-Id", "u").header("X-User-Roles", "CLIENTE,FAKE"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(servicio);
    }

    @Test
    void administracionFallaCerradaHastaIntegrarMs1() throws Exception {
        doThrow(new Ms1NoDisponibleException("MS1 no disponible")).when(coordinacion).configurar(anyString(), anyString(), anyBoolean(), any());
        doThrow(new Ms1NoDisponibleException("MS1 no disponible")).when(coordinacion).cambiarEstado(anyString(), anyString(), anyBoolean(), any());
        when(coordinacion.consultarFila(anyString(), anyString(), anyBoolean())).thenThrow(new Ms1NoDisponibleException("MS1 no disponible"));
        for (String rol : new String[]{"ADMIN", "ORGANIZADOR"}) {
            mvc.perform(put("/filas/eventos/e/configuracion").header("X-User-Id", "u").header("X-User-Roles", rol)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"cupo\":10,\"duracionMinutos\":5}"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value(503));
            mvc.perform(put("/filas/eventos/e/estado").header("X-User-Id", "u").header("X-User-Roles", rol)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"ACTIVA\"}"))
                    .andExpect(status().isServiceUnavailable());
        }
        mvc.perform(get("/filas/eventos/e").header("X-User-Id", "u").header("X-User-Roles", "ORGANIZADOR"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(servicio);
    }

    @Test
    void administradorConsultaFilaSinVerificacionDePropiedad() throws Exception {
        when(coordinacion.consultarFila("e", "admin", true)).thenReturn(new FilaEventoResponse("e", EstadoFila.ACTIVA, false, 10, 5, 1, 2));
        mvc.perform(get("/filas/eventos/e").header("X-User-Id", "admin").header("X-User-Roles", "CLIENTE, ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cantidadHabilitados").value(2));
    }

    @Test
    void internosExigenToken() throws Exception {
        mvc.perform(post("/internal/filas/eventos/e/consumos")).andExpect(status().isForbidden());
        mvc.perform(put("/internal/filas/eventos/e/cancelacion").header("X-Service-Token", "incorrecto"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(servicio);
    }

    @Test
    void internosUsanBodyValidadoYCancelacionEsSinContenido() throws Exception {
        when(servicio.consumir(eq("e"), any())).thenReturn(new ConsumoTurnoResponse(1L, "e", "s", EstadoTurno.CONSUMIDO, ahora, false));
        mvc.perform(post("/internal/filas/eventos/e/consumos").header("X-Service-Token", "token-prueba").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuarioId\":\"u\",\"solicitudId\":\"s\",\"huellaSolicitud\":\"h\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.autorizacionVigente").value(false));
        mvc.perform(put("/internal/filas/eventos/e/cancelacion").header("X-Service-Token", "token-prueba")).andExpect(status().isNoContent());
        verify(servicio).cancelar("e");
    }

    @Test
    void validacionJsonYMediosUsanFormatoDeErrores() throws Exception {
        mvc.perform(post("/internal/filas/eventos/e/consumos").header("X-Service-Token", "token-prueba").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuarioId\":\"u\",\"solicitudId\":\"\",\"huellaSolicitud\":\"h\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("solicitudId"));
        mvc.perform(post("/internal/filas/eventos/e/consumos").header("X-Service-Token", "token-prueba").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuarioId\":\"u\",\"solicitudId\":\"s\",\"huellaSolicitud\":\"h\",\"extra\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos").doesNotExist());
        mvc.perform(put("/filas/eventos/e/configuracion").header("X-User-Id", "u").header("X-User-Roles", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"cupo\":0,\"duracionMinutos\":5}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("cupo"));
        mvc.perform(post("/internal/filas/eventos/e/consumos").header("X-Service-Token", "token-prueba").contentType(MediaType.TEXT_PLAIN).content("hola"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error").value("Unsupported Media Type"));
        verifyNoInteractions(servicio);
    }

    @Test
    void erroresInesperadosNoExponenDetalles() throws Exception {
        when(servicio.consultarTurno("e", "u")).thenThrow(new IllegalStateException("dato sensible"));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FilaControllerAdvice.class);
        var capturado = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        capturado.start(); logger.addAppender(capturado);
        try {
            mvc.perform(get("/filas/eventos/e/mi-turno").header("X-User-Id", "u").header("X-User-Roles", "CLIENTE"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.mensaje").value("Ocurrió un error interno."));
            org.junit.jupiter.api.Assertions.assertTrue(capturado.list.stream().anyMatch(event ->
                    event.getFormattedMessage().contains("java.lang.IllegalStateException")
                            && !event.getFormattedMessage().contains("dato sensible")));
        } finally { logger.detachAppender(capturado); capturado.stop(); }
    }
}
