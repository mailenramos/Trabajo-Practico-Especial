// Simulador mínimo del servicio de Reservas, solo para pruebas locales.
// Uso:  node reservas-stub.js        (escucha en el puerto 8080; la variable PORT lo cambia)
//
// Implementa los contratos acordados:
//   PUT /disponibilidad/eventos/{eventoId}        -> inicializa la disponibilidad (204; 409 si difiere)
//   PUT /disponibilidad/eventos/{eventoId}/venta  -> cambia el estado de venta (204)
//   GET /disponibilidad/eventos/{eventoId}        -> consulta la disponibilidad actual (200; 404 si no está inicializado)
// Valida el body y escribe en consola lo que recibe, para comprobar el formato que envía el otro servicio.
//
// Rutas extra, solo del simulador (no existen en el servicio real), para poder probar el detalle del evento:
//   POST   /_stub/ocupar/{eventoId}   ocupa lugares: {"sectorId":"...","cantidad":3}  (sector no numerado)
//                                     o {"sectorId":"...","asientos":[{"fila":1,"numero":1}]}  (sector numerado)
//   DELETE /_stub/eventos             borra todo lo inicializado

const http = require("http");

const PORT = process.env.PORT || 8080;
const ESTADOS = ["ACTIVA", "SUSPENDIDA", "CANCELADA", "FINALIZADA"];
const inicializados = new Map(); // eventoId -> { canon, body, estado, ocupados: Map(sectorId -> { cantidad, asientos:Set }) }

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

const esEntero = (n) => Number.isInteger(n);
const clave = (a) => `${a.fila}-${a.numero}`;

function validarInicializacion(b) {
  if (!b || typeof b !== "object") return "El body debe ser un objeto JSON";
  if (!esEntero(b.limiteEntradasPorCliente) || b.limiteEntradasPorCliente < 1) {
    return "limiteEntradasPorCliente debe ser un entero mayor o igual a 1";
  }
  if (!Array.isArray(b.sectores) || b.sectores.length === 0) return "sectores debe ser una lista no vacía";
  for (const s of b.sectores) {
    if (typeof s.sectorId !== "string" || !s.sectorId) return "sectorId es obligatorio (string)";
    if (!["NUMERADO", "NO_NUMERADO"].includes(s.tipo)) return `tipo inválido en el sector ${s.sectorId}`;
    if (!esEntero(s.capacidadTotal) || s.capacidadTotal < 1) return `capacidadTotal inválida en el sector ${s.sectorId}`;
    if (!Array.isArray(s.asientos)) return `asientos debe ser una lista en el sector ${s.sectorId}`;
    if (s.tipo === "NO_NUMERADO" && s.asientos.length !== 0) {
      return `el sector no numerado ${s.sectorId} debe enviar asientos vacío`;
    }
    if (s.tipo === "NUMERADO") {
      if (s.asientos.length !== s.capacidadTotal) {
        return `el sector ${s.sectorId} tiene ${s.asientos.length} asientos pero capacidadTotal ${s.capacidadTotal}`;
      }
      const vistos = new Set();
      for (const a of s.asientos) {
        if (!esEntero(a.fila) || !esEntero(a.numero) || a.fila < 1 || a.numero < 1) {
          return `asiento inválido en ${s.sectorId}: fila y numero deben ser enteros mayores o iguales a 1`;
        }
        if (vistos.has(clave(a))) return `asiento repetido en ${s.sectorId}: fila ${a.fila}, numero ${a.numero}`;
        vistos.add(clave(a));
      }
    }
  }
  return null;
}

function resumen(b) {
  if (!b || !Array.isArray(b.sectores)) return "";
  const asientos = b.sectores.reduce((t, s) => t + (Array.isArray(s.asientos) ? s.asientos.length : 0), 0);
  return ` (sectores: ${b.sectores.length}, asientos enviados: ${asientos}, limite: ${b.limiteEntradasPorCliente})`;
}

function disponibilidad(id, evento) {
  return {
    eventoId: id,
    sectores: evento.body.sectores.map((s) => {
      const ocu = evento.ocupados.get(s.sectorId) || { cantidad: 0, asientos: new Set() };
      if (s.tipo === "NUMERADO") {
        const libres = s.asientos.filter((a) => !ocu.asientos.has(clave(a)));
        return {
          sectorId: s.sectorId,
          tipo: s.tipo,
          capacidadTotal: s.capacidadTotal,
          cantidadDisponible: libres.length,
          asientosDisponibles: libres,
        };
      }
      return {
        sectorId: s.sectorId,
        tipo: s.tipo,
        capacidadTotal: s.capacidadTotal,
        cantidadDisponible: s.capacidadTotal - ocu.cantidad,
      };
    }),
  };
}

function ocupar(res, id, evento, b) {
  if (!b || typeof b.sectorId !== "string") return error(res, 400, "sectorId es obligatorio");
  const sector = evento.body.sectores.find((s) => s.sectorId === b.sectorId);
  if (!sector) return error(res, 404, `El sector ${b.sectorId} no existe en el evento`);
  let ocu = evento.ocupados.get(sector.sectorId);
  if (!ocu) {
    ocu = { cantidad: 0, asientos: new Set() };
    evento.ocupados.set(sector.sectorId, ocu);
  }
  if (sector.tipo === "NO_NUMERADO") {
    if (!esEntero(b.cantidad) || b.cantidad < 1) return error(res, 400, "cantidad debe ser un entero mayor o igual a 1");
    if (ocu.cantidad + b.cantidad > sector.capacidadTotal) return error(res, 409, "No hay tantos lugares disponibles");
    ocu.cantidad += b.cantidad;
  } else {
    if (!Array.isArray(b.asientos) || b.asientos.length === 0) return error(res, 400, "asientos debe ser una lista no vacía");
    const existentes = new Set(sector.asientos.map(clave));
    for (const a of b.asientos) {
      if (!existentes.has(clave(a))) return error(res, 404, `El asiento ${clave(a)} no existe en el sector`);
      if (ocu.asientos.has(clave(a))) return error(res, 409, `El asiento ${clave(a)} ya está ocupado`);
    }
    b.asientos.forEach((a) => ocu.asientos.add(clave(a)));
  }
  console.log(`  -> 204 lugares ocupados en el sector ${sector.sectorId}`);
  return responder(res, 204);
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
    console.log(`${new Date().toISOString()} ${req.method} ${ruta}${resumen(body)}`);

    let m = ruta.match(/^\/disponibilidad\/eventos\/([^/]+)$/);
    if (m && req.method === "PUT") {
      if (jsonInvalido) return error(res, 400, "JSON inválido");
      const problema = validarInicializacion(body);
      if (problema) return error(res, 400, problema);
      const id = m[1];
      const canon = JSON.stringify(body);
      if (inicializados.has(id)) {
        if (inicializados.get(id).canon === canon) {
          console.log("  -> 204 reintento con los mismos datos");
          return responder(res, 204);
        }
        return error(res, 409, "El evento ya estaba inicializado con datos diferentes");
      }
      inicializados.set(id, { canon, body, estado: "ACTIVA", ocupados: new Map() });
      console.log("  -> 204 inicializado");
      return responder(res, 204);
    }

    if (m && req.method === "GET") {
      const evento = inicializados.get(m[1]);
      if (!evento) return error(res, 404, "No hay disponibilidad registrada para ese evento");
      const resp = disponibilidad(m[1], evento);
      console.log(`  -> 200 disponibilidad de ${resp.sectores.length} sectores`);
      return responder(res, 200, resp);
    }

    m = ruta.match(/^\/disponibilidad\/eventos\/([^/]+)\/venta$/);
    if (m && req.method === "PUT") {
      if (jsonInvalido || !body || !ESTADOS.includes(body.estado)) {
        return error(res, 400, `estado inválido (valores: ${ESTADOS.join(", ")})`);
      }
      const evento = inicializados.get(m[1]);
      if (!evento) return error(res, 404, "No hay disponibilidad inicializada para ese evento");
      evento.estado = body.estado;
      console.log(`  -> 204 estado de venta: ${body.estado}`);
      return responder(res, 204);
    }

    m = ruta.match(/^\/_stub\/ocupar\/([^/]+)$/);
    if (m && req.method === "POST") {
      if (jsonInvalido) return error(res, 400, "JSON inválido");
      const evento = inicializados.get(m[1]);
      if (!evento) return error(res, 404, "No hay disponibilidad registrada para ese evento");
      return ocupar(res, m[1], evento, body);
    }

    if (ruta === "/_stub/eventos" && req.method === "DELETE") {
      inicializados.clear();
      console.log("  -> 204 todo borrado");
      return responder(res, 204);
    }

    return error(res, 404, "Ruta no implementada en el simulador");
  });
});

server.listen(PORT, () => {
  console.log(`Simulador de Reservas escuchando en http://localhost:${PORT}`);
  console.log("Para detenerlo: Ctrl+C");
});
