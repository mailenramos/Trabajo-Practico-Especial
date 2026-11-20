package ticketya.cliente.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import ticketya.cliente.ContratoInvalidoException;
import ticketya.cliente.EntradasCliente;
import ticketya.cliente.EstadoEntradaConflictoException;
import ticketya.cliente.EventoCerradoException;
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

    private static final Logger log = LoggerFactory.getLogger(EntradasClienteRest.class);

    /**
     * Todas las llamadas llevan X-Service-Token (SERVICIO_TOKEN) y X-Correlation-Id. Sin token
     * configurado no se envía el encabezado: Entradas responde 403, que es una falla técnica
     * reintentable (se corrige configurando el token).
     */
    @Autowired
    public EntradasClienteRest(@Value("${servicios.entradas.url}") String url,
                               @Value("${compras.seguridad.token-servicio:}") String token,
                               @Value("${servicios.timeout-conexion:2s}") Duration conexion,
                               @Value("${servicios.timeout-lectura:5s}") Duration lectura) {
        super("Entradas", url, conexion, lectura, new EncabezadosDeServicio(token));
        if (token == null || token.isBlank()) {
            log.warn("SERVICIO_TOKEN no está configurado: Entradas va a rechazar las llamadas con 403");
        }
    }

    /** Sin token de servicio (pruebas del contrato que no dependen de los encabezados). */
    EntradasClienteRest(String url, Duration conexion, Duration lectura) {
        this(url, null, conexion, lectura);
    }

    /**
     * Un 409 EVENTO_CERRADO es un conflicto de negocio ({@link EventoCerradoException}, sin
     * reintentos); cualquier otro 409 (por ejemplo, IDEMPOTENCIA_CONFLICTO) sigue siendo una falla.
     */
    @Override
    public List<EntradaEmitida> emitir(SolicitudEmision solicitud) {
        JsonNode respuesta = llamar("emitir", () -> http.post().uri("/internal/entradas/emision")
                .body(solicitud).retrieve().body(JsonNode.class),
                (status, ex) -> {
                    if (status.value() == 409 && "EVENTO_CERRADO".equals(codigoDeError(ex))) {
                        throw new EventoCerradoException(
                                solicitud.evento() == null ? null : solicitud.evento().id(), ex);
                    }
                    return null;
                });
        if (respuesta == null) {
            throw new ServicioExternoException("Entradas", "Entradas respondió sin cuerpo");
        }
        return leerEntradas(respuesta);
    }

    /**
     * GET /internal/compras/{idCompra}/entradas (Entrega 2): consulta sin emitir. Una lista vacía
     * (o un 2xx sin cuerpo) es "no se emitió". La envoltura se lee igual que en la emisión.
     */
    @Override
    public List<EntradaEmitida> listarPorCompra(String idCompra) {
        JsonNode respuesta = llamar("listarPorCompra",
                () -> http.get().uri("/internal/compras/{idCompra}/entradas", idCompra).retrieve().body(JsonNode.class));
        return respuesta == null ? List.of() : leerEntradas(respuesta);
    }

    /** Lista de entradas, como arreglo o dentro de "entradas". El sector puede venir plano o anidado. */
    private static List<EntradaEmitida> leerEntradas(JsonNode respuesta) {
        JsonNode lista = respuesta.isArray() ? respuesta : respuesta.path("entradas");
        List<EntradaEmitida> entradas = new ArrayList<>();
        for (JsonNode e : lista) {
            String sector = e.has("idSector") ? texto(e, "idSector")
                    : e.has("sectorId") ? texto(e, "sectorId")
                    : e.path("sector").isObject() ? texto(e.path("sector"), "id") : texto(e, "sector");
            entradas.add(new EntradaEmitida(texto(e, "idEntrada"), sector, entero(e, "fila"), entero(e, "numero"),
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
     * POST /internal/eventos/{idEvento}/entradas/reintegro (Entrega 2). Responde
     * {@code entradasMarcadas} y {@code cerrado}. Un 2xx sin entradasMarcadas cuenta como 0, porque
     * el dato es solo informativo; sin cerrado (o sin cuerpo) el cierre NO está confirmado.
     */
    @Override
    public ResultadoCierre marcarEntradasDelEvento(String idEvento) {
        JsonNode respuesta = llamar("marcarEntradasDelEvento",
                () -> http.post().uri("/internal/eventos/{idEvento}/entradas/reintegro", idEvento)
                        .retrieve().body(JsonNode.class));
        if (respuesta == null) {
            return new ResultadoCierre(0, false);
        }
        Integer marcadas = entero(respuesta, "entradasMarcadas");
        JsonNode cerrado = respuesta.path("cerrado");
        return new ResultadoCierre(marcadas == null ? 0 : marcadas, cerrado.isBoolean() && cerrado.asBoolean());
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
