package ticketya.envio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ticketya.modelo.Notificacion;
import ticketya.modelo.TipoNotificacion;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlantillasTest {

    static final Instant AHORA = Instant.parse("2026-11-17T12:00:00Z");
    final Plantillas plantillas = new Plantillas();

    Correo generar(TipoNotificacion tipo, Map<String, Object> datos) {
        Notificacion n = Notificacion.nueva("ac-1", tipo, "u-1", "recurso-9", "corr-1", 1, AHORA, datos, AHORA);
        return plantillas.generar(n, "ana@example.com");
    }

    @Test
    void compraConfirmada() {
        Correo c = generar(TipoNotificacion.COMPRA_CONFIRMADA,
                Map.of("evento", "Recital", "fecha", "20/12/2026", "cantidad", 2, "total", "30000"));

        assertThat(c.destinatario()).isEqualTo("ana@example.com");
        assertThat(c.asunto()).isEqualTo("Compra confirmada: Recital");
        assertThat(c.cuerpo()).contains("Recital del 20/12/2026 fue confirmada", "Cantidad de entradas: 2",
                "Total pagado: $30000", "Referencia: recurso-9");
    }

    @Test
    void entradasDisponibles() {
        Correo c = generar(TipoNotificacion.ENTRADAS_DISPONIBLES, Map.of("evento", "Recital"));

        assertThat(c.asunto()).contains("Recital", "disponibles");
        assertThat(c.cuerpo()).contains("descargar tus entradas para Recital");
    }

    @Test
    void eventoCancelado() {
        Correo c = generar(TipoNotificacion.EVENTO_CANCELADO, Map.of("evento", "Recital", "fecha", "20/12/2026"));

        assertThat(c.asunto()).isEqualTo("Evento cancelado: Recital");
        assertThat(c.cuerpo()).contains("Recital del 20/12/2026 fue cancelado", "reintegro");
    }

    @Test
    void eventoModificadoIndicaLosDatosNuevos() {
        Correo c = generar(TipoNotificacion.EVENTO_MODIFICADO,
                Map.of("evento", "Recital", "nuevaFecha", "21/12/2026", "nuevoHorario", "22:00",
                        "nuevoLugar", "Estadio Norte"));

        assertThat(c.asunto()).isEqualTo("Cambios en Recital");
        assertThat(c.cuerpo()).contains("- Fecha: 21/12/2026", "- Horario: 22:00", "- Lugar: Estadio Norte");
    }

    @Test
    void eventoModificadoSoloMuestraLoQueCambio() {
        Correo c = generar(TipoNotificacion.EVENTO_MODIFICADO, Map.of("evento", "Recital", "nuevoLugar", "Estadio Norte"));

        assertThat(c.cuerpo()).contains("- Lugar: Estadio Norte").doesNotContain("- Fecha:", "- Horario:");
    }

    @Test
    void nuevoEventoDeInteres() {
        Correo c = generar(TipoNotificacion.NUEVO_EVENTO_DE_INTERES,
                Map.of("evento", "Festival", "fecha", "05/01/2027", "lugar", "Parque Sur"));

        assertThat(c.asunto()).contains("Festival");
        assertThat(c.cuerpo()).contains("Se publicó Festival del 05/01/2027 en Parque Sur");
    }

    @Test
    void devolucionAprobada() {
        Correo c = generar(TipoNotificacion.DEVOLUCION_APROBADA, Map.of("evento", "Recital", "monto", "15000"));

        assertThat(c.asunto()).isEqualTo("Devolución aprobada: Recital");
        assertThat(c.cuerpo()).contains("Monto a reintegrar: $15000");
    }

    @Test
    void devolucionRechazada() {
        Correo c = generar(TipoNotificacion.DEVOLUCION_RECHAZADA, Map.of("evento", "Recital", "motivo", "Fuera de plazo"));

        assertThat(c.asunto()).isEqualTo("Devolución rechazada: Recital");
        assertThat(c.cuerpo()).contains("Motivo: Fuera de plazo");
    }

    @Test
    void reintegroExitosoYFallido() {
        Correo ok = generar(TipoNotificacion.RESULTADO_REINTEGRO, Map.of("monto", "15000", "resultado", "EXITOSO"));
        Correo mal = generar(TipoNotificacion.RESULTADO_REINTEGRO, Map.of("monto", "15000", "resultado", "FALLIDO"));

        assertThat(ok.asunto()).isEqualTo("Reintegro acreditado");
        assertThat(ok.cuerpo()).contains("Se acreditó tu reintegro de $15000");
        assertThat(mal.asunto()).isEqualTo("No pudimos acreditar tu reintegro");
    }

    @ParameterizedTest
    @EnumSource(TipoNotificacion.class)
    void todoTipoGeneraUnCorreoAunqueFaltenDatos(TipoNotificacion tipo) {
        Correo c = generar(tipo, Map.of());

        assertThat(c.asunto()).isNotBlank().doesNotContain("null");
        assertThat(c.cuerpo()).isNotBlank().doesNotContain("null").endsWith(Plantillas.FIRMA);
    }
}
