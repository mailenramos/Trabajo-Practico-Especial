// Simulador de Compras (MS3) para los avisos que envía MS1 (cancelación y, más adelante, modificación).
// Uso: node postman/compras-eventos-stub.js   (puerto 8083 por defecto; PORT lo cambia)
// No se puede usar a la vez que compras-stub.js de MS8: ambos usan el puerto 8083 (cambialo con PORT).
//
// POST /eventos-cancelados  (también acepta /internal/eventos-cancelados)
//   Body JSON con el aviso de cancelación (se registra tal cual). Deduplica por X-Aviso-Id.
//   -> 202 la primera vez, 200 si ya se había recibido ese aviso.
// POST /internal/eventos-modificados     contrato acordado con MS3:
//   Body: eventoId y version (entero >= 1) obligatorios; fechaHora (ISO-8601 UTC), lugar y ciudad opcionales,
//   con al menos uno. Deduplica por eventoId + version; una versión menor a la ya procesada se ignora.
//   -> 202 nueva; 200 ya procesada o anterior; 400 body inválido; respuesta { eventoId, version, resultado }
//
// Ambas: X-Service-Token es obligatorio (403 si falta). Si SERVICIO_TOKEN está definida, debe coincidir.
//
// Diagnóstico y simulación de fallos:
//   GET    /_stub/avisos                lista los avisos recibidos (con X-Aviso-Id, token presente y correlación)
//   DELETE /_stub/avisos                limpia avisos y versiones
//   POST   /_stub/modo/{ok|error|timeout}   error responde 500; timeout no responde

const http = require('http');

const PORT = process.env.PORT || 8083;
const TOKEN = process.env.SERVICIO_TOKEN || null;

const avisos = [];
const vistos = new Set();       // X-Aviso-Id de cancelaciones ya recibidas
const versiones = new Map();    // eventoId -> última versión procesada
let modo = 'ok';

function responder(res, status, cuerpo) {
  if (cuerpo === undefined) { res.writeHead(status); res.end(); return; }
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(cuerpo));
}

function tokenValido(req) {
  const t = req.headers['x-service-token'];
  if (!t) return false;
  return TOKEN === null || t === TOKEN;
}

function registrar(req, ruta, cuerpo, resultado) {
  avisos.push({
    recibidoEn: new Date().toISOString(),
    ruta,
    avisoId: req.headers['x-aviso-id'] || null,
    token: req.headers['x-service-token'] ? 'presente' : 'ausente',
    correlationId: req.headers['x-correlation-id'] || null,
    cuerpo,
    resultado,
  });
  console.log(`[${new Date().toISOString()}] POST ${ruta} aviso=${req.headers['x-aviso-id'] || '-'} -> ${resultado} modo=${modo}`);
}

const texto = (v) => typeof v === 'string' && v.trim() !== '';

const servidor = http.createServer((req, res) => {
  let datos = '';
  req.on('data', (p) => (datos += p));
  req.on('end', () => {
    const ruta = req.url.split('?')[0];
    let cuerpo = null;
    if (datos) { try { cuerpo = JSON.parse(datos); } catch (e) { cuerpo = { _invalido: datos }; } }

    if (req.method === 'POST' && /^(\/internal)?\/eventos-cancelados$/.test(ruta)) {
      if (!tokenValido(req)) { registrar(req, ruta, cuerpo, '403'); return responder(res, 403, { codigo: 'ACCESO_DENEGADO' }); }
      if (modo === 'timeout') { registrar(req, ruta, cuerpo, 'sin respuesta'); return; }
      if (modo === 'error') { registrar(req, ruta, cuerpo, '500'); return responder(res, 500, { codigo: 'ERROR_INESPERADO' }); }
      const id = req.headers['x-aviso-id'] || JSON.stringify(cuerpo);
      const repetido = vistos.has(id);
      vistos.add(id);
      registrar(req, ruta, cuerpo, repetido ? '200 repetido' : '202');
      return responder(res, repetido ? 200 : 202, { recibido: true, duplicado: repetido });
    }

    if (req.method === 'POST' && ruta === '/internal/eventos-modificados') {
      if (!tokenValido(req)) { registrar(req, ruta, cuerpo, '403'); return responder(res, 403, { codigo: 'ACCESO_DENEGADO' }); }
      if (modo === 'timeout') { registrar(req, ruta, cuerpo, 'sin respuesta'); return; }
      if (modo === 'error') { registrar(req, ruta, cuerpo, '500'); return responder(res, 500, { codigo: 'ERROR_INESPERADO' }); }
      const b = cuerpo || {};
      const algunCampo = texto(b.fechaHora) || texto(b.lugar) || texto(b.ciudad);
      const valido = texto(b.eventoId) && Number.isInteger(b.version) && b.version >= 1 && algunCampo &&
        (b.fechaHora === undefined || !Number.isNaN(Date.parse(b.fechaHora)));
      if (!valido) {
        registrar(req, ruta, cuerpo, '400');
        return responder(res, 400, { codigo: 'VALIDACION', mensaje: 'eventoId y version (entero >= 1) obligatorios; al menos uno de fechaHora, lugar, ciudad' });
      }
      const ultima = versiones.get(b.eventoId) || 0;
      if (b.version <= ultima) {
        registrar(req, ruta, cuerpo, '200 ya procesada o anterior');
        return responder(res, 200, { eventoId: b.eventoId, version: b.version, resultado: b.version === ultima ? 'YA_PROCESADA' : 'IGNORADA_ANTERIOR' });
      }
      versiones.set(b.eventoId, b.version);
      registrar(req, ruta, cuerpo, '202');
      return responder(res, 202, { eventoId: b.eventoId, version: b.version, resultado: 'PROCESADA' });
    }

    if (ruta === '/_stub/avisos') {
      if (req.method === 'GET') return responder(res, 200, avisos);
      if (req.method === 'DELETE') { avisos.length = 0; vistos.clear(); versiones.clear(); return responder(res, 204); }
    }
    const m = ruta.match(/^\/_stub\/modo\/(ok|error|timeout)$/);
    if (req.method === 'POST' && m) { modo = m[1]; return responder(res, 200, { modo }); }

    responder(res, 404, { mensaje: 'Ruta no implementada en el stub' });
  });
});

servidor.listen(PORT, () => console.log(`Stub de Compras (MS3) para MS1 escuchando en el puerto ${PORT}${TOKEN ? ' (token exigido)' : ''}`));
