package ar.edu.unicen.ms_fila_virtual.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoFila;
import ar.edu.unicen.ms_fila_virtual.service.FilaVirtualService;
import ar.edu.unicen.ms_fila_virtual.service.Ms1EventoClient;
import ar.edu.unicen.ms_fila_virtual.service.Ms1NoDisponibleException;

/** Runs only against a newly created, dedicated disposable MySQL server. */
@EnabledIfEnvironmentVariable(named = "MS7_TEST_MYSQL_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class FilaHttpMySqlTests {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        String url = System.getenv("MS7_TEST_MYSQL_URL");
        String password = System.getenv("MS7_TEST_MYSQL_PASSWORD");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ms7_disposable")
                || url.contains(":3307/") || password == null) {
            throw new IllegalStateException("Se requiere una instancia MySQL descartable dedicada");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FilaVirtualService servicio;
    @MockitoBean Ms1EventoClient ms1;

    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM turnos");
        jdbc.update("DELETE FROM filas_evento");
    }

    @Test
    void ingresoConsultaConsumoYCancelacionFuncionanConPersistenciaReal() throws Exception {
        when(ms1.consultar("e")).thenReturn(new Ms1EventoClient.Evento("e", "org", "publicado", true));
        servicio.cambiarEstadoLocal("e", EstadoFila.ACTIVA);
        mvc.perform(post("/filas/eventos/e/ingresos").header("X-User-Id", "u").header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("HABILITADO"));
        mvc.perform(get("/filas/eventos/e/mi-turno").header("X-User-Id", "u").header("X-User-Roles", "CLIENTE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("HABILITADO"));
        String body = "{\"usuarioId\":\"u\",\"solicitudId\":\"s\",\"huellaSolicitud\":\"h\"}";
        mvc.perform(post("/internal/filas/eventos/e/consumos").header("X-Service-Token", "token-prueba").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.autorizacionVigente").value(true));
        mvc.perform(put("/internal/filas/eventos/e/cancelacion").header("X-Service-Token", "token-prueba")).andExpect(status().isNoContent());
        mvc.perform(post("/internal/filas/eventos/e/consumos").header("X-Service-Token", "token-prueba").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.autorizacionVigente").value(false));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM turnos WHERE estado='CONSUMIDO'", Long.class));
    }

    @Test
    void administracionPendienteNoPersisteCambios() throws Exception {
        when(ms1.consultar(anyString())).thenThrow(new Ms1NoDisponibleException("MS1 no disponible"));
        mvc.perform(put("/filas/eventos/e/configuracion").header("X-User-Id", "u").header("X-User-Roles", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"cupo\":10,\"duracionMinutos\":5}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(put("/filas/eventos/e/estado").header("X-User-Id", "u").header("X-User-Roles", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"ACTIVA\"}"))
                .andExpect(status().isServiceUnavailable());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM filas_evento", Long.class));
    }
}
