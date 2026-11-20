package ticketya.envio;

import org.springframework.stereotype.Component;
import ticketya.modelo.Notificacion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Una plantilla de texto plano en español por tipo de notificación, con los datos del aviso.
 * <p>
 * Campos de {@code datos} propuestos por tipo (contrato a acordar con el grupo):
 * <ul>
 *   <li>COMPRA_CONFIRMADA: evento, fecha, cantidad, total</li>
 *   <li>ENTRADAS_DISPONIBLES: evento, fecha</li>
 *   <li>EVENTO_CANCELADO: evento, fecha</li>
 *   <li>EVENTO_MODIFICADO: evento y al menos uno de nuevaFecha, nuevoHorario, nuevoLugar</li>
 *   <li>NUEVO_EVENTO_DE_INTERES: evento, fecha, lugar</li>
 *   <li>DEVOLUCION_APROBADA: evento, monto</li>
 *   <li>DEVOLUCION_RECHAZADA: evento, motivo</li>
 *   <li>RESULTADO_REINTEGRO: monto, resultado (EXITOSO o FALLIDO)</li>
 * </ul>
 * Si falta un dato, se usa un texto genérico en lugar de fallar: el correo igual es útil.
 */
@Component
public class Plantillas {

    static final String FIRMA = "\n\nEquipo de TicketYa\nEste es un mensaje automático, no lo respondas.";

    public Correo generar(Notificacion n, String destinatario) {
        Map<String, Object> d = n.getDatos();
        String evento = texto(d, "evento", "tu evento");
        String referencia = "\n\nReferencia: " + n.getRecursoId();
        return switch (n.getTipo()) {
            case COMPRA_CONFIRMADA -> new Correo(destinatario, "Compra confirmada: " + evento,
                    "Tu compra para " + evento + fechaOpcional(d, "fecha") + " fue confirmada."
                            + opcional(d, "cantidad", "\nCantidad de entradas: ")
                            + opcional(d, "total", "\nTotal pagado: $")
                            + referencia + FIRMA);
            case ENTRADAS_DISPONIBLES -> new Correo(destinatario, "Tus entradas para " + evento + " ya están disponibles",
                    "Ya podés ver y descargar tus entradas para " + evento + fechaOpcional(d, "fecha")
                            + " desde tu cuenta de TicketYa." + referencia + FIRMA);
            case EVENTO_CANCELADO -> new Correo(destinatario, "Evento cancelado: " + evento,
                    "Lamentamos informarte que " + evento + fechaOpcional(d, "fecha") + " fue cancelado. "
                            + "Vas a recibir el reintegro de tus entradas por el mismo medio de pago." + referencia + FIRMA);
            case EVENTO_MODIFICADO -> new Correo(destinatario, "Cambios en " + evento,
                    evento + " tuvo cambios. Estos son los datos nuevos:" + cambios(d)
                            + "\n\nTus entradas siguen siendo válidas." + referencia + FIRMA);
            case NUEVO_EVENTO_DE_INTERES -> new Correo(destinatario, "Nuevo evento que te puede interesar: " + evento,
                    "Se publicó " + evento + fechaOpcional(d, "fecha") + opcional(d, "lugar", " en ")
                            + ". Entrá a TicketYa para conseguir tus entradas." + FIRMA);
            case DEVOLUCION_APROBADA -> new Correo(destinatario, "Devolución aprobada: " + evento,
                    "Aprobamos la devolución de tus entradas para " + evento + "."
                            + opcional(d, "monto", "\nMonto a reintegrar: $")
                            + "\nEl reintegro se acredita por el mismo medio de pago." + referencia + FIRMA);
            case DEVOLUCION_RECHAZADA -> new Correo(destinatario, "Devolución rechazada: " + evento,
                    "Tu solicitud de devolución de entradas para " + evento + " fue rechazada."
                            + opcional(d, "motivo", "\nMotivo: ") + referencia + FIRMA);
            case RESULTADO_REINTEGRO -> resultadoReintegro(d, destinatario, referencia);
        };
    }

    private Correo resultadoReintegro(Map<String, Object> d, String destinatario, String referencia) {
        boolean exitoso = "EXITOSO".equalsIgnoreCase(texto(d, "resultado", ""));
        String monto = opcional(d, "monto", " de $");
        if (exitoso) {
            return new Correo(destinatario, "Reintegro acreditado",
                    "Se acreditó tu reintegro" + monto + "." + referencia + FIRMA);
        }
        return new Correo(destinatario, "No pudimos acreditar tu reintegro",
                "No pudimos acreditar tu reintegro" + monto + ". Lo vamos a volver a intentar; "
                        + "si persiste, contactá a soporte." + referencia + FIRMA);
    }

    private static String cambios(Map<String, Object> d) {
        List<String> lineas = new ArrayList<>();
        if (presente(d, "nuevaFecha")) lineas.add("\n- Fecha: " + d.get("nuevaFecha"));
        if (presente(d, "nuevoHorario")) lineas.add("\n- Horario: " + d.get("nuevoHorario"));
        if (presente(d, "nuevoLugar")) lineas.add("\n- Lugar: " + d.get("nuevoLugar"));
        if (lineas.isEmpty()) {
            return "\n- Consultá los detalles actualizados en TicketYa.";
        }
        return String.join("", lineas);
    }

    private static String fechaOpcional(Map<String, Object> d, String clave) {
        return opcional(d, clave, " del ");
    }

    private static String opcional(Map<String, Object> d, String clave, String prefijo) {
        return presente(d, clave) ? prefijo + d.get(clave) : "";
    }

    private static String texto(Map<String, Object> d, String clave, String porDefecto) {
        return presente(d, clave) ? String.valueOf(d.get(clave)) : porDefecto;
    }

    private static boolean presente(Map<String, Object> d, String clave) {
        Object valor = d.get(clave);
        return valor != null && !String.valueOf(valor).isBlank();
    }
}
