package com.ticketya.gestionlugares.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.Sector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.service.LugarService;
import com.ticketya.gestionlugares.service.ResourceNotFoundException;

@WebMvcTest(LugarController.class)
@Import(ApiExceptionHandler.class)
class LugarControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LugarService lugarService;

    @Test
    void administradorPuedeCrearLugar() throws Exception {
        when(lugarService.crearLugar(any())).thenReturn(new Lugar("507f1f77bcf86cd799439011",
                "Teatro", "Calle 1", null, -34.0, -58.0, null));

        mockMvc.perform(post("/lugares")
                        .header("X-User-Id", "admin-1")
                        .header("X-User-Role", "administrador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Teatro","direccion":"Calle 1","latitud":-34.0,"longitud":-58.0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("507f1f77bcf86cd799439011"));
    }

    @Test
    void rechazaFaltaDeIdentidadYRolNoAdministrador() throws Exception {
        String body = """
                {"nombre":"Teatro","direccion":"Calle 1","latitud":-34.0,"longitud":-58.0}
                """;

        mockMvc.perform(post("/lugares").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/lugares").header("X-User-Id", "cliente-1")
                        .header("X-User-Role", "cliente")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void rechazaCoordenadasInvalidas() throws Exception {
        mockMvc.perform(post("/lugares").header("X-User-Id", "admin-1")
                        .header("X-User-Role", "administrador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Teatro","direccion":"Calle 1","latitud":91,"longitud":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aceptaLimitesGeograficosInclusivos() throws Exception {
        when(lugarService.crearLugar(any())).thenReturn(new Lugar("507f1f77bcf86cd799439011",
                "Polo Sur", "Calle 1", null, 90.0, 180.0, null));

        mockMvc.perform(post("/lugares")
                        .header("X-User-Id", "admin-1")
                        .header("X-User-Role", "administrador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Polo Sur","direccion":"Calle 1","latitud":90,"longitud":180}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void agregaSectorYResponde201() throws Exception {
        when(lugarService.agregarSector(eq("507f1f77bcf86cd799439011"), any()))
                .thenReturn(new Sector("550e8400-e29b-41d4-a716-446655440000", "Platea",
                        TipoSector.NUMERADO, 2, 5, null));

        mockMvc.perform(post("/lugares/507f1f77bcf86cd799439011/sectores")
                        .header("X-User-Id", "admin-1")
                        .header("X-User-Role", "administrador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Platea","tipo":"NUMERADO","filas":2,"asientosPorFila":5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    void responde404CuandoElLugarNoExiste() throws Exception {
        when(lugarService.buscarLugar("507f1f77bcf86cd799439011"))
                .thenThrow(new ResourceNotFoundException("No existe el lugar"));

        mockMvc.perform(get("/lugares/507f1f77bcf86cd799439011"))
                .andExpect(status().isNotFound());
    }

    @Test
    void responde409AnteConflictoConcurrenteDeActualizacion() throws Exception {
        when(lugarService.agregarSector(eq("507f1f77bcf86cd799439011"), any()))
                .thenThrow(new OptimisticLockingFailureException("conflicto"));

        mockMvc.perform(post("/lugares/507f1f77bcf86cd799439011/sectores")
                        .header("X-User-Id", "admin-1")
                        .header("X-User-Role", "administrador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Platea","tipo":"NUMERADO","filas":2,"asientosPorFila":5}
                                """))
                .andExpect(status().isConflict());
    }
}
