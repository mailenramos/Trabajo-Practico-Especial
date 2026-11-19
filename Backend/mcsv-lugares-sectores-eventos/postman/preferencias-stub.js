// Simulador de Preferencias y recomendaciones (MS8) para probar los avisos de publicación de MS1.
// Uso: node postman/preferencias-stub.js   (puerto 8089 por defecto; PORT lo cambia)
//
// POST   /internal/preferencias/eventos-publicados  recibe el aviso. 202 si es nuevo, 200 si ya lo conocía (mismo eventoId).
// GET    /_stub/avisos                              lista los avisos recibidos.
// DELETE /_stub/avisos                              limpia los avisos.
// POST   /_stub/modo/{ok|error|timeout}             simula fallos: error responde 503; timeout no responde.
//
// Con "error" o "timeout" se prueba que la publicación sigue respondiendo 200 y que el aviso queda
// PENDIENTE en la outbox de MS1; con "ok" se prueba el reenvío.

const http = require('http');

const PORT = process.env.PORT || 8089;
const SERVICIO_TOKEN = process.env.SERVICIO_TOKEN;
const RUTA_AVISO = '/internal/preferencias/eventos-publicados';
const CAMPOS = ['eventoId', 'nombre', 'tipo', 'participantes', 'ciudad', 'fechaHora'];

const avisos = [];
const vistos = new Set();
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

const servidor = http.createServer(async (req, res) => {
  const { method, url } = req;
  const ruta = url.split('?')[0];

  if (method === 'POST' && ruta === RUTA_AVISO) {
    if (SERVICIO_TOKEN && SERVICIO_TOKEN.trim() && req.headers['x-service-token'] !== SERVICIO_TOKEN) {
      return responder(res, 403, { error: 'Token de servicio inválido' });
    }
    const crudo = await leerCuerpo(req);
    const avisoId = req.headers['x-aviso-id'] || null;
    console.log(`[${new Date().toISOString()}] POST ${ruta} X-Aviso-Id=${avisoId} modo=${modo}`);

    if (modo === 'timeout') return; // no responde: MS1 corta por su timeout de lectura
    if (modo === 'error') return responder(res, 503, { error: 'MS8 simulado no disponible' });

    let cuerpo;
    try {
      cuerpo = JSON.parse(crudo);
    } catch (e) {
      console.log('  cuerpo no es JSON válido');
      return responder(res, 400, { error: 'JSON inválido' });
    }

    const faltantes = CAMPOS.filter((campo) => cuerpo[campo] === undefined || cuerpo[campo] === null);
    if (faltantes.length > 0) {
      console.log(`  faltan campos: ${faltantes.join(', ')}`);
      return responder(res, 400, { error: `Faltan campos: ${faltantes.join(', ')}` });
    }
    if (!Array.isArray(cuerpo.participantes)) {
      return responder(res, 400, { error: 'participantes debe ser una lista' });
    }

    const nuevo = !vistos.has(cuerpo.eventoId);
    vistos.add(cuerpo.eventoId);
    avisos.push({ recibidoEn: new Date().toISOString(), avisoId, nuevo, cuerpo });
    console.log(`  evento ${cuerpo.eventoId} "${cuerpo.nombre}" (${cuerpo.ciudad}) -> ${nuevo ? 'nuevo' : 'ya conocido'}`);
    return responder(res, nuevo ? 202 : 200);
  }

  if (method === 'GET' && ruta === '/_stub/avisos') {
    return responder(res, 200, { modo, total: avisos.length, avisos });
  }

  if (method === 'DELETE' && ruta === '/_stub/avisos') {
    avisos.length = 0;
    vistos.clear();
    console.log('Avisos limpiados');
    return responder(res, 204);
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
  console.log(`Stub de Preferencias (MS8) escuchando en http://localhost:${PORT}`);
  console.log(`Aviso de publicación: POST ${RUTA_AVISO}`);
});
