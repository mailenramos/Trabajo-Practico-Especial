package ticketya.cliente.rest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import ticketya.cliente.ContratoInvalidoException;
import ticketya.cliente.EntradasCliente;
import ticketya.cliente.EstadoEntradaConflictoException;
import ticketya.cliente.ServicioExternoException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cliente de Entradas (servicio 5), con el contrato confirmado por ese servicio.
 *
 * POST /internal/entradas/emision, idempotente por idCompra (201 nueva, 200 repetición,
 * 409 IDEMPOTENCIA_CONFLICTO). El pedido lleva idCompra, idCliente, evento {id, nombre,
 * comienzaEn} y lugares [{idSector, nombreSector, fila, numero}]. La envoltura de la respuesta
 * (lista u objeto con "entradas") no se fijó, por eso se lee de forma tolerante.
 *
 * Errores: formato del grupo, con el código en "error"; mientras Entradas migra, también se
 * acepta en "codigo". Un 409 en un cambio de estado es un conflicto de estado
 * ({@link EstadoEntradaConflictoException}), distinto de una caída.
 */
@Component
public class EntradasClienteRest extends ClienteRest implements EntradasCliente {

    public EntradasClienteRest(@Value("${servicios.entradas.url}") String url,
                               @Value("${servicios.timeout-conexion:2s}") Duration conexion,
                               @Value("${servicios.timeout-lectura:5s}") Duration lectura) {
        super("Entradas", url, conexion, lectura);
    }

    @Override
    public List<EntradaEmitida> emitir(SolicitudEmision solicitud) {
        JsonNode respuesta = llamar("emitir", () -> http.post().uri("/internal/entradas/emision")
                .body(solicitud).retrieve().body(JsonNode.class));
        if (respuesta == null) {
            throw new ServicioExternoException("Entradas", "Entradas respondió sin cuerpo");
        }
        JsonNode lista = respuesta.isArray() ? respuesta : respuesta.path("entradas");
        List<EntradaEmitida> entradas = new ArrayList<>();
        for (JsonNode e : lista) {
            entradas.add(new EntradaEmitida(
                    texto(e, "idEntrada"),
                    e.has("idSector") ? texto(e, "idSector")
                            : e.has("sectorId") ? texto(e, "sectorId") : texto(e, "sector"),
                    entero(e, "fila"),
                    entero(e, "numero"),
                    texto(e, "codigoQr")));
        }
        return entradas;
    }

    /**
     * GET /internal/entradas/{id}. Vacío solo ante 404 con código ENTRADA_NO_ENCONTRADA (en
     * "error" o en "codigo"); un 404 sin ese código (por ejemplo, una ruta mal configurada) es
     * una falla del servicio.
     */
    @Override
    public Optional<Entrada> consultar(String idEntrada) {
        JsonNode e = llamar("consultar",
                () -> http.get().uri("/internal/entradas/{id}", idEntrada).retrieve().body(JsonNode.class),
                (status, ex) -> status.value() == 404 && "ENTRADA_NO_ENCONTRADA".equals(codigoDeError(ex))
                        ? NO_EXISTE : null);
        if (e == null || e == NO_EXISTE) {
            return Optional.empty();
        }
        JsonNode evento = e.path("evento");
        JsonNode sector = e.path("sector");
        return Optional.of(new Entrada(
                e.hasNonNull("idEntrada") ? texto(e, "idEntrada") : idEntrada,
                texto(e, "idCliente"),
                texto(e, "idCompra"),
                EstadoEntrada.de(texto(e, "estado")),
                texto(evento, "id"),
                fecha(texto(evento, "comienzaEn")),
                texto(sector, "id"),
                texto(sector, "nombre"),
                entero(e, "fila"),
                entero(e, "numero"),
                texto(e, "codigoQr")));
    }

    private static Instant fecha(String texto) {
        try {
            return texto == null ? null : Instant.parse(texto);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** POST /internal/entradas/{id}/reintegro. 200 también al repetir; 409 TRANSICION_INVALIDA es conflicto. */
    @Override
    public void pasarAEnReintegro(String idEntrada) {
        cambiarEstado(idEntrada, "reintegro", "pasar a en reintegro");
    }

    /** POST /internal/entradas/{id}/devolucion. 200 también al repetir; 409 TRANSICION_INVALIDA es conflicto. */
    @Override
    public void pasarADevuelta(String idEntrada) {
        cambiarEstado(idEntrada, "devolucion", "pasar a devuelta");
    }

    /** Un 409 es un conflicto de estado (el contrato solo define TRANSICION_INVALIDA); otra falla, del servicio. */
    private void cambiarEstado(String idEntrada, String accion, String operacion) {
        llamar(operacion, () -> http.post().uri("/internal/entradas/{id}/" + accion, idEntrada)
                .retrieve().toBodilessEntity(),
                (status, ex) -> {
                    if (status.value() == 409) {
                        throw new EstadoEntradaConflictoException(idEntrada, operacion, codigoDeError(ex), ex);
                    }
                    return null;
                });
    }

    /**
     * POST /internal/eventos/{idEvento}/entradas/reintegro. PROVISORIO: no figura en el contrato
     * confirmado por Entradas; se mantiene la propuesta anterior (contrato a acordar).
     * Responde la cantidad marcada en entradasMarcadas; un 2xx sin ese campo cuenta como 0,
     * porque el dato es solo informativo.
     */
    @Override
    public int marcarEntradasDelEvento(String idEvento) {
        JsonNode respuesta = llamar("marcarEntradasDelEvento",
                () -> http.post().uri("/internal/eventos/{idEvento}/entradas/reintegro", idEvento)
                        .retrieve().body(JsonNode.class));
        if (respuesta == null) {
            return 0;
        }
        Integer marcadas = entero(respuesta, "entradasMarcadas");
        return marcadas == null ? 0 : marcadas;
    }

    /** Marca interna para un 404, distinta de un cuerpo vacío. */
    private static final JsonNode NO_EXISTE = tools.jackson.databind.node.JsonNodeFactory.instance.missingNode();

    /**
     * Un campo entero opcional (fila, número). Acepta un número entero o un texto numérico;
     * cualquier otro valor (por ejemplo, la fila "A") es un error de contrato.
     */
    private static Integer entero(JsonNode nodo, String campo) {
        if (!nodo.hasNonNull(campo)) {
            return null;
        }
        JsonNode valor = nodo.get(campo);
        if (valor.isIntegralNumber() && valor.canConvertToInt()) {
            return valor.asInt();
        }
        if (valor.isString() && valor.asString().trim().matches("-?\\d{1,9}")) {
            return Integer.valueOf(valor.asString().trim());
        }
        throw new ContratoInvalidoException("Entradas",
                "Entradas devolvió " + campo + " = " + valor + ", que no es un número entero");
    }

    private static String texto(JsonNode nodo, String campo) {
        return nodo.hasNonNull(campo) ? nodo.get(campo).asString() : null;
    }
}
