// Simulador mínimo del servicio de Compras y Devoluciones, solo para pruebas locales.
// Uso:  node compras-stub.js        (escucha en el puerto 8083; la variable PORT lo cambia)
//
// Implementa el contrato acordado:
//   POST /eventos-cancelados   body: {"eventoId","motivo","canceladoEn"}
//     202 si acepta el aviso, 200 si ya lo había procesado (idempotente), 400 si el body es inválido.
//
// Rutas extra, solo del simulador (no existen en el servicio real), para poder comprobar desde Postman
// qué avisos llegaron:
//   GET    /_stub/avisos   lista los avisos recibidos
//   DELETE /_stub/avisos   borra los avisos recibidos

const http = require("http");

const PORT = process.env.PORT || 8083;
const avisos = new Map(); // eventoId -> { eventoId, motivo, canceladoEn, recibidoEn, veces }

function responder(res, status, body) {
  if (body === undefined) {
    res.writeHead(status);
    return res.end();
  }
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

function error(res, status, mensaje) {
  console.log(`  -> ${status} ${mensaje}`);
  responder(res, status, {
    timestamp: new Date().toISOString(),
    status,
    error: http.STATUS_CODES[status],
    mensaje,
  });
}

const noVacio = (s) => typeof s === "string" && s.trim().length > 0;

function validarAviso(b) {
  if (!b || typeof b !== "object") return "El body debe ser un objeto JSON";
  if (!noVacio(b.eventoId)) return "eventoId es obligatorio (string)";
  if (!noVacio(b.motivo)) return "motivo es obligatorio (string no vacío)";
  if (!noVacio(b.canceladoEn) || !/^\d{4}-\d{2}-\d{2}T.*Z$/.test(b.canceladoEn) || isNaN(Date.parse(b.canceladoEn))) {
    return "canceladoEn debe ser una fecha ISO-8601 en UTC (termina en Z)";
  }
  return null;
}

const server = http.createServer((req, res) => {
  let data = "";
  req.on("data", (c) => (data += c));
  req.on("end", () => {
    const ruta = req.url.split("?")[0];
    let body;
    let jsonInvalido = false;
    if (data) {
      try {
        body = JSON.parse(data);
      } catch (e) {
        jsonInvalido = true;
      }
    }
    console.log(`${new Date().toISOString()} ${req.method} ${ruta}${body && body.eventoId ? " eventoId=" + body.eventoId : ""}`);

    if (req.method === "POST" && ruta === "/eventos-cancelados") {
      if (jsonInvalido) return error(res, 400, "JSON inválido");
      const problema = validarAviso(body);
      if (problema) return error(res, 400, problema);
      const previo = avisos.get(body.eventoId);
      if (previo) {
        previo.veces += 1;
        console.log(`  -> 200 aviso ya procesado (recibido ${previo.veces} veces)`);
        return responder(res, 200);
      }
      avisos.set(body.eventoId, {
        eventoId: body.eventoId,
        motivo: body.motivo,
        canceladoEn: body.canceladoEn,
        recibidoEn: new Date().toISOString(),
        veces: 1,
      });
      console.log(`  -> 202 aviso aceptado (motivo: ${body.motivo})`);
      return responder(res, 202);
    }

    if (req.method === "GET" && ruta === "/_stub/avisos") {
      return responder(res, 200, Array.from(avisos.values()));
    }

    if (req.method === "DELETE" && ruta === "/_stub/avisos") {
      avisos.clear();
      console.log("  -> 204 avisos borrados");
      return responder(res, 204);
    }

    return error(res, 404, "Ruta no implementada en el simulador");
  });
});

server.listen(PORT, () => {
  console.log(`Simulador de Compras escuchando en http://localhost:${PORT}`);
  console.log("Avisos recibidos: GET /_stub/avisos   |   Para detenerlo: Ctrl+C");
});
