// Simulador de Compras y devoluciones (MS3) para probar las recomendaciones de MS8.
// Uso: node postman/compras-stub.js   (puerto 8083 por defecto; PORT lo cambia)
//
// Endpoints que simula (internos; exigen el header X-Service-Token):
//   GET /internal/usuarios/{usuarioId}/historial-preferencias
//       -> 200 { usuarioId, tipos: [{tipo, cantidad}], eventosComprados: [eventoId, ...] }
//   GET /internal/eventos/{eventoId}/co-compras?limite=10
//       -> 200 { eventoId, coCompras: [{eventoId, cantidad}] }  (mayor a menor, máximo 10, tope 20,
//          sin incluir el evento consultado)
//
// Token: si la variable SERVICIO_TOKEN está definida, el header debe coincidir; si no está definida,
// alcanza con que el header X-Service-Token esté presente. Sin token válido responde 401.
//
// Datos de prueba (en memoria):
//   PUT    /_stub/usuarios/{usuarioId}              body { tipos: [...], eventosComprados: [...] }
//   PUT    /_stub/eventos/{eventoId}/co-compras     body { coCompras: [{eventoId, cantidad}] }
//   DELETE /_stub/datos                             borra los datos y el registro de llamadas
// Diagnóstico:
//   GET    /_stub/llamadas                          lista las llamadas internas recibidas
//   POST   /_stub/modo/{ok|error|timeout}           error responde 503; timeout no responde
//
// Un usuario o evento sin datos cargados devuelve listas vacías (200), como Compras real.

const http = require('http');

const PORT = process.env.PORT || 8083;
const TOKEN = process.env.SERVICIO_TOKEN || null;
const LIMITE_POR_DEFECTO = 10;
const LIMITE_MAXIMO = 20;
const MAX_EVENTOS_COMPRADOS = 20;

const usuarios = new Map();
const coCompras = new Map();
const llamadas = [];
let modo = 'ok';

function responder(res, status, cuerpo) {
  if (cuerpo === undefined) {
    res.writeHead(status);
    res.end();
    return;
  }
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(cuerpo));
}

function leerCuerpo(req) {
  return new Promise((resolve) => {
    let datos = '';
    req.on('data', (parte) => (datos += parte));
    req.on('end', () => resolve(datos));
  });
}

function tokenValido(req) {
  const recibido = req.headers['x-service-token'];
  if (!recibido) return false;
  return TOKEN === null || recibido === TOKEN;
}

function registrar(req, ruta) {
  llamadas.push({
    recibidaEn: new Date().toISOString(),
    metodo: req.method,
    ruta,
    tokenPresente: Boolean(req.headers['x-service-token']),
    tokenValido: tokenValido(req),
  });
}

const servidor = http.createServer(async (req, res) => {
  const { method, url } = req;
  const [ruta, consulta = ''] = url.split('?');
  const parametros = new URLSearchParams(consulta);

  // --- Endpoints internos de Compras ---
  const historial = ruta.match(/^\/internal\/usuarios\/([^/]+)\/historial-preferencias$/);
  const coCompra = ruta.match(/^\/internal\/eventos\/([^/]+)\/co-compras$/);

  if (method === 'GET' && (historial || coCompra)) {
    registrar(req, ruta + (consulta ? `?${consulta}` : ''));
    console.log(`[${new Date().toISOString()}] GET ${ruta} token=${req.headers['x-service-token'] ? 'sí' : 'NO'} modo=${modo}`);

    if (!tokenValido(req)) return responder(res, 401, { error: 'X-Service-Token ausente o inválido' });
    if (modo === 'timeout') return; // no responde: el cliente corta por su timeout de lectura
    if (modo === 'error') return responder(res, 503, { error: 'Compras simulado no disponible' });

    if (historial) {
      const usuarioId = decodeURIComponent(historial[1]);
      const datos = usuarios.get(usuarioId) || { tipos: [], eventosComprados: [] };
      const tipos = [...datos.tipos].sort((a, b) => b.cantidad - a.cantidad);
      return responder(res, 200, {
        usuarioId,
        tipos,
        eventosComprados: datos.eventosComprados.slice(0, MAX_EVENTOS_COMPRADOS),
      });
    }

    const eventoId = decodeURIComponent(coCompra[1]);
    let limite = parseInt(parametros.get('limite'), 10);
    if (!Number.isInteger(limite) || limite < 1) limite = LIMITE_POR_DEFECTO;
    limite = Math.min(limite, LIMITE_MAXIMO);
    const lista = (coCompras.get(eventoId) || [])
      .filter((item) => item.eventoId !== eventoId)
      .sort((a, b) => b.cantidad - a.cantidad)
      .slice(0, limite);
    return responder(res, 200, { eventoId, coCompras: lista });
  }

  // --- Datos de prueba ---
  const cargaUsuario = ruta.match(/^\/_stub\/usuarios\/([^/]+)$/);
  if (method === 'PUT' && cargaUsuario) {
    try {
      const cuerpo = JSON.parse(await leerCuerpo(req));
      const tipos = (cuerpo.tipos || []).map((t) =>
        typeof t === 'string' ? { tipo: t, cantidad: 1 } : { tipo: t.tipo, cantidad: t.cantidad || 1 });
      const eventosComprados = [...new Set(cuerpo.eventosComprados || [])];
      const usuarioId = decodeURIComponent(cargaUsuario[1]);
      usuarios.set(usuarioId, { tipos, eventosComprados });
      console.log(`Usuario ${usuarioId} cargado: ${tipos.length} tipos, ${eventosComprados.length} eventos comprados`);
      return responder(res, 200, { usuarioId, tipos, eventosComprados });
    } catch (e) {
      return responder(res, 400, { error: 'JSON inválido' });
    }
  }

  const cargaCoCompras = ruta.match(/^\/_stub\/eventos\/([^/]+)\/co-compras$/);
  if (method === 'PUT' && cargaCoCompras) {
    try {
      const cuerpo = JSON.parse(await leerCuerpo(req));
      const lista = (cuerpo.coCompras || []).map((c) => ({ eventoId: c.eventoId, cantidad: c.cantidad || 1 }));
      const eventoId = decodeURIComponent(cargaCoCompras[1]);
      coCompras.set(eventoId, lista);
      console.log(`Co-compras de ${eventoId} cargadas: ${lista.length}`);
      return responder(res, 200, { eventoId, coCompras: lista });
    } catch (e) {
      return responder(res, 400, { error: 'JSON inválido' });
    }
  }

  if (method === 'DELETE' && ruta === '/_stub/datos') {
    usuarios.clear();
    coCompras.clear();
    llamadas.length = 0;
    console.log('Datos y llamadas limpiados');
    return responder(res, 204);
  }

  // --- Diagnóstico ---
  if (method === 'GET' && ruta === '/_stub/llamadas') {
    return responder(res, 200, { modo, total: llamadas.length, llamadas });
  }

  const cambioModo = ruta.match(/^\/_stub\/modo\/(ok|error|timeout)$/);
  if (method === 'POST' && cambioModo) {
    modo = cambioModo[1];
    console.log(`Modo cambiado a ${modo}`);
    return responder(res, 200, { modo });
  }

  responder(res, 404, { error: `Ruta no simulada: ${method} ${ruta}` });
});

servidor.listen(PORT, () => {
  console.log(`Stub de Compras (para MS8) escuchando en http://localhost:${PORT}`);
  console.log(TOKEN === null
    ? 'SERVICIO_TOKEN no definida: se acepta cualquier X-Service-Token presente'
    : 'SERVICIO_TOKEN definida: el header debe coincidir');
});
