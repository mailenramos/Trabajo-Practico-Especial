package com.ticketya.gestionlugares.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ticketya.gestionlugares.repository.LugarRepository;

@SpringBootTest(properties = "spring.data.mongodb.auto-index-creation=false")
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @MockBean
    private LugarRepository lugarRepository;

    @Test
    void publicaOperacionesParametrosRespuestasYEjemplosOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("TicketYa - Gestión de lugares, sectores y eventos"))
                .andExpect(jsonPath("$.paths['/lugares'].post").exists())
                .andExpect(jsonPath("$.paths['/lugares'].get").exists())
                .andExpect(jsonPath("$.paths['/lugares/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/lugares/{id}/sectores'].get").exists())
                .andExpect(jsonPath("$.paths['/lugares/{id}/sectores'].post").exists())
                .andExpect(jsonPath("$.paths['/eventos'].post").exists())
                .andExpect(jsonPath("$.paths['/eventos/{eventoId}/sectores/{sectorId}/precio'].put").exists())
                .andExpect(jsonPath("$.paths['/eventos/{eventoId}/limite-entradas-por-cliente'].put").exists())
                .andExpect(jsonPath("$.paths['/lugares'].get.parameters[?(@.name == 'X-User-Role')].schema.enum").exists())
                .andExpect(jsonPath("$.paths['/lugares'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/lugares'].get.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/lugares'].post.requestBody.content['application/json'].examples.Lugar").exists())
                .andExpect(jsonPath("$.paths['/lugares/{id}/sectores'].post.requestBody.content['application/json'].examples['Sector numerado']").exists())
                .andExpect(jsonPath("$.paths['/lugares/{id}/sectores'].post.requestBody.content['application/json'].examples['Sector no numerado']").exists())
                .andExpect(jsonPath("$.paths['/lugares/{id}/sectores'].post.responses['404']").exists());
    }

    @Test
    void elPuertoPredeterminadoEs8081() {
        org.junit.jupiter.api.Assertions.assertEquals("8081", environment.getProperty("server.port"));
    }
}
