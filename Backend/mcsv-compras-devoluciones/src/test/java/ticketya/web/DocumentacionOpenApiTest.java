package ticketya.web;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import ticketya.PruebaIntegracion;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La documentación OpenAPI se publica, es YAML válido, cubre los endpoints y sus $ref existen. */
class DocumentacionOpenApiTest extends PruebaIntegracion {

    @Test
    void laDocumentacionSePublicaEnOpenapiYaml() throws Exception {
        mvc.perform(get("/openapi.yaml")).andExpect(status().isOk());
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentaLosCuatroEndpointsYTodasLasReferenciasExisten() throws Exception {
        Map<String, Object> doc;
        try (InputStream in = getClass().getResourceAsStream("/static/openapi.yaml")) {
            doc = new Yaml().load(in);
        }

        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) doc.get("paths");
        assertThat(paths.get("/compras")).containsKeys("post", "get");
        assertThat(paths.get("/compras/{compraId}")).containsKey("get");
        assertThat(paths.get("/compras/{compraId}/pago")).containsKey("post");
        assertThat(paths.get("/devoluciones")).containsKeys("post", "get");
        assertThat(paths.get("/devoluciones/{devolucionId}")).containsKey("get");
        assertThat(paths.get("/devoluciones/{devolucionId}/aprobacion")).containsKey("post");
        assertThat(paths.get("/devoluciones/{devolucionId}/rechazo")).containsKey("post");
        assertThat(paths.get("/reintegros")).containsKey("get");
        assertThat(paths.get("/reintegros/{reintegroId}/reintento")).containsKey("post");

        // Entrega 2: identidad del Gateway con X-User-Roles; X-User-Role queda obsoleto
        Map<String, Map<String, Object>> parametros = (Map<String, Map<String, Object>>)
                ((Map<String, Object>) doc.get("components")).get("parameters");
        assertThat(parametros.get("XUserRoles")).containsEntry("name", "X-User-Roles");
        assertThat(parametros.get("XUserRole")).containsEntry("deprecated", true);
        assertThat(paths.get("/devoluciones/{devolucionId}/aprobacion").get("post").toString())
                .contains("#/components/parameters/XUserRolesAdmin");

        // Entrega 2: consultas internas con X-Service-Token
        Map<String, Object> coCompras = (Map<String, Object>) paths.get("/internal/eventos/{eventoId}/co-compras").get("get");
        assertThat((Map<Object, Object>) coCompras.get("responses")).containsKeys("200", "400", "403");
        assertThat((List<Object>) coCompras.get("security")).isNotEmpty();
        Map<String, Object> historial = (Map<String, Object>)
                paths.get("/internal/usuarios/{usuarioId}/historial-preferencias").get("get");
        assertThat((Map<Object, Object>) historial.get("responses")).containsKeys("200", "403");
        assertThat((List<Object>) historial.get("security")).isNotEmpty();
        Map<String, Object> modificacion = (Map<String, Object>) paths.get("/internal/eventos-modificados").get("post");
        assertThat(modificacion).containsKey("requestBody");
        assertThat((Map<Object, Object>) modificacion.get("responses")).containsKeys("200", "202", "400", "403");
        assertThat((List<Object>) modificacion.get("security")).isNotEmpty();

        // E-1: aviso de cancelación con sus tres respuestas y el origen del reintegro
        Map<String, Object> aviso = (Map<String, Object>) paths.get("/eventos-cancelados").get("post");
        assertThat(aviso).containsKey("requestBody");
        assertThat((Map<Object, Object>) aviso.get("responses")).containsKeys("200", "202", "400");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
        Map<String, Object> origen = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) schemas.get("Reintegro")).get("properties")).get("origen");
        assertThat((List<String>) origen.get("enum")).contains("CANCELACION_EVENTO");
        assertThat(ejemplosConReferencia(doc)).as("OpenAPI no admite $ref dentro de 'example'").isEmpty();

        List<String> referencias = new ArrayList<>();
        juntarReferencias(doc, referencias);
        assertThat(referencias).isNotEmpty();
        for (String ref : referencias) {
            assertThat(resolver(doc, ref)).as("la referencia %s existe", ref).isNotNull();
        }
    }

    /** Rutas de usuario con JWT (lo valida el Gateway); internas con token de servicio; el aviso de cancelación, sin nada. */
    @Test
    @SuppressWarnings("unchecked")
    void declaraBearerParaLasRutasDeUsuarioYTokenDeServicioParaLasInternas() throws Exception {
        Map<String, Object> doc;
        try (InputStream in = getClass().getResourceAsStream("/static/openapi.yaml")) {
            doc = new Yaml().load(in);
        }
        Map<String, Object> esquemas = (Map<String, Object>)
                ((Map<String, Object>) doc.get("components")).get("securitySchemes");
        assertThat((Map<String, Object>) esquemas.get("bearerAuth"))
                .containsEntry("type", "http").containsEntry("scheme", "bearer").containsEntry("bearerFormat", "JWT");
        assertThat((Map<String, Object>) esquemas.get("TokenDeServicio"))
                .containsEntry("in", "header").containsEntry("name", "X-Service-Token");

        List<Map<String, Object>> global = (List<Map<String, Object>>) doc.get("security");
        assertThat(global).hasSize(1);
        assertThat(global.get(0)).containsKey("bearerAuth");

        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) doc.get("paths");

        // Las rutas de usuario heredan el bearer global: ninguna lo reemplaza
        List<String> deUsuario = List.of("/compras", "/compras/{compraId}", "/compras/{compraId}/pago",
                "/devoluciones", "/devoluciones/{devolucionId}", "/devoluciones/{devolucionId}/aprobacion",
                "/devoluciones/{devolucionId}/rechazo", "/reintegros", "/reintegros/{reintegroId}/reintento");
        for (String ruta : deUsuario) {
            paths.get(ruta).forEach((metodo, operacion) -> {
                if (List.of("get", "post", "put", "delete").contains(metodo)) {
                    assertThat((Map<String, Object>) operacion)
                            .as("%s %s hereda el bearer global", metodo, ruta).doesNotContainKey("security");
                }
            });
        }

        // Las internas usan el token de servicio, no el JWT
        for (String ruta : List.of("/internal/eventos/{eventoId}/co-compras",
                "/internal/usuarios/{usuarioId}/historial-preferencias")) {
            Map<String, Object> get = (Map<String, Object>) paths.get(ruta).get("get");
            assertThat(((List<Map<String, Object>>) get.get("security")).get(0)).containsKey("TokenDeServicio");
        }
        Map<String, Object> modificacion = (Map<String, Object>) paths.get("/internal/eventos-modificados").get("post");
        assertThat(((List<Map<String, Object>>) modificacion.get("security")).get(0)).containsKey("TokenDeServicio");

        // El aviso de cancelación no lleva ni JWT ni token de servicio
        Map<String, Object> cancelacion = (Map<String, Object>) paths.get("/eventos-cancelados").get("post");
        assertThat((List<Object>) cancelacion.get("security")).isEmpty();
    }

    /** Reportes para organizadores: GET con bearer heredado, roles de reportes y sus cinco respuestas. */
    @Test
    @SuppressWarnings("unchecked")
    void documentaLosReportesParaOrganizadores() throws Exception {
        Map<String, Object> doc;
        try (InputStream in = getClass().getResourceAsStream("/static/openapi.yaml")) {
            doc = new Yaml().load(in);
        }
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) doc.get("paths");
        for (String reporte : List.of("ventas", "devoluciones", "recaudacion", "ocupacion")) {
            String ruta = "/reportes/eventos/{eventoId}/" + reporte;
            assertThat(paths).as(ruta).containsKey(ruta);
            Map<String, Object> get = (Map<String, Object>) paths.get(ruta).get("get");
            assertThat(get).as(ruta).isNotNull().doesNotContainKey("security");
            assertThat((List<String>) get.get("tags")).containsExactly("Reportes");
            assertThat((Map<Object, Object>) get.get("responses")).as(ruta)
                    .containsKeys("200", "401", "403", "404", "503");
            assertThat(get.get("parameters").toString())
                    .contains("#/components/parameters/EventoId", "#/components/parameters/XUserRolesReportes");
        }
        Map<String, Object> respuestas = (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("responses");
        assertThat(respuestas.get("SinAccesoAReportes").toString()).contains("SIN_PERMISO_REPORTES", "EVENTO_AJENO");
        assertThat(respuestas.get("EventoNoEncontrado").toString()).contains("EVENTO_NO_ENCONTRADO");
    }

    @SuppressWarnings("unchecked")
    private static void juntarReferencias(Object nodo, List<String> referencias) {
        if (nodo instanceof Map<?, ?> mapa) {
            mapa.forEach((k, v) -> {
                if ("$ref".equals(k)) {
                    referencias.add((String) v);
                } else {
                    juntarReferencias(v, referencias);
                }
            });
        } else if (nodo instanceof List<?> lista) {
            lista.forEach(e -> juntarReferencias(e, referencias));
        }
    }

    /** Valores de claves 'example' que contienen un $ref en algún nivel. */
    private static List<Object> ejemplosConReferencia(Object nodo) {
        List<Object> encontrados = new ArrayList<>();
        if (nodo instanceof Map<?, ?> mapa) {
            mapa.forEach((k, v) -> {
                if ("example".equals(k)) {
                    List<String> refs = new ArrayList<>();
                    juntarReferencias(v, refs);
                    if (!refs.isEmpty()) {
                        encontrados.add(v);
                    }
                } else {
                    encontrados.addAll(ejemplosConReferencia(v));
                }
            });
        } else if (nodo instanceof List<?> lista) {
            lista.forEach(e -> encontrados.addAll(ejemplosConReferencia(e)));
        }
        return encontrados;
    }

    @SuppressWarnings("unchecked")
    private static Object resolver(Map<String, Object> doc, String ref) {
        Object actual = doc;
        for (String parte : ref.substring(2).split("/")) {
            if (!(actual instanceof Map<?, ?> mapa)) {
                return null;
            }
            actual = ((Map<String, Object>) mapa).get(parte);
        }
        return actual;
    }
}
