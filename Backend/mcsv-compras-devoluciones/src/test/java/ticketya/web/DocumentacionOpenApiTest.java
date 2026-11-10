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
