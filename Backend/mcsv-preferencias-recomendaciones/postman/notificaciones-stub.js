// Simulador de Notificaciones (MS9) para probar los avisos de MS8.
// Uso: node postman/notificaciones-stub.js   (puerto 8090 por defecto; PORT lo cambia)
//
// POST /internal/notificaciones   recibe un aviso por destinatario (JSON).
//   Header X-Service-Token: si SERVICIO_TOKEN está definida debe coincidir; si no, alcanza con que esté presente.
//   Sin token válido -> 403. Content-Type distinto de JSON -> 415.
//   Body: acontecimientoId (máx 100), tipo, versionEsquema (=1), ocurridoEn, recursoId, usuarioId,
//         correlacionId, datos { evento, fecha, lugar }. Inválido -> 400.
//   Deduplica por acontecimientoId + tipo + usuarioId.
//   -> 202 { id, estado: "PENDIENTE", duplicado: false|true }
//
// Diagnóstico y simulación de fallos:
//   GET    /_stub/notificaciones          lista los avisos recibidos
//   DELETE /_stub/notificaciones          limpia avisos y usuarios con falla
//   POST   /_stub/modo/{ok|error|timeout} error responde 500; timeout no responde
//   PUT    /_stub/fallar/{usuarioId}      los avisos de ese usuario responden 500 (prueba de falla parcial)
//   DELETE /_stub/fallar                  quita los usuarios con falla

const http = require('http');
const crypto = require('crypto');

const PORT = process.env.PORT || 8090;
const TOKEN = process.env.SERVICIO_TOKEN || null;
const RUTA = '/internal/notificaciones';

const avisos = new Map(); // clave -> { id, recibidoEn, cuerpo, reentregas }
const usuariosConFalla = new Set();
let modo = 'ok';

function responder(res, status, cuerpo) {
  if (cuerpo === undefined) { res.writeHead(status); res.end(); return; }
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(cuerpo));
}

function leerCuerpo(req) {
  return new Promise((resolve) => {
    let datos = '';
    req.on('data', (p) => (datos += p));
    req.on('end', () => resolve(datos));
  });
}

function tokenValido(req) {
  const recibido = req.headers['x-service-token'];
  if (!recibido) return false;
  return TOKEN === null || recibido === TOKEN;
}

const texto = (v) => typeof v === 'string' && v.trim() !== '';

function validar(c) {
  const faltan = ['acontecimientoId', 'tipo', 'ocurridoEn', 'recursoId', 'usuarioId'].filter((k) => !texto(c[k]));
  if (c.versionEsquema === undefined || c.versionEsquema === null) faltan.push('versionEsquema');
  if (!c.datos || typeof c.datos !== 'object') faltan.push('datos');
  else ['evento', 'fecha', 'lugar'].forEach((k) => { if (!texto(c.datos[k])) faltan.push(`datos.${k}`); });
  if (faltan.length) return { codigo: 'VALIDACION', mensaje: `Campos faltantes o vacíos: ${faltan.join(', ')}` };
  if (c.acontecimientoId.length > 100) return { codigo: 'VALIDACION', mensaje: 'acontecimientoId supera 100 caracteres' };
  if (c.versionEsquema !== 1) return { codigo: 'ESQUEMA_NO_SOPORTADO', mensaje: 'versionEsquema debe ser 1' };
  if (Number.isNaN(Date.parse(c.ocurridoEn))) return { codigo: 'VALIDACION', mensaje: 'ocurridoEn no es una fecha ISO-8601' };
  return null;
}

const servidor = http.createServer(async (req, res) => {
  const { method, url } = req;
  const ruta = url.split('?')[0];

  if (method === 'POST' && ruta === RUTA) {
    const crudo = await leerCuerpo(req);
    console.log(`[${new Date().toISOString()}] POST ${ruta} token=${req.headers['x-service-token'] ? 'sí' : 'NO'} modo=${modo}`);

    if (!tokenValido(req)) return responder(res, 403, { codigo: 'ACCESO_DENEGADO', mensaje: 'X-Service-Token ausente o inválido' });
    if (modo === 'timeout') return;
    if (modo === 'error') return responder(res, 500, { codigo: 'ERROR_INESPERADO' });
    if (!String(req.headers['content-type'] || '').toLowerCase().startsWith('application/json')) {
      return responder(res, 415, { codigo: 'TIPO_NO_SOPORTADO', mensaje: 'Se espera application/json' });
    }

    let cuerpo;
    try { cuerpo = JSON.parse(crudo); } catch (e) {
      return responder(res, 400, { codigo: 'CUERPO_INVALIDO', mensaje: 'JSON inválido' });
    }
    if (cuerpo === null || typeof cuerpo !== 'object' || Array.isArray(cuerpo)) {
      return responder(res, 400, { codigo: 'CUERPO_INVALIDO', mensaje: 'Se espera un objeto JSON' });
    }
    const error = validar(cuerpo);
    if (error) { console.log(`  400 ${error.codigo}: ${error.mensaje}`); return responder(res, 400, error); }

    if (usuariosConFalla.has(cuerpo.usuarioId)) {
      console.log(`  usuario ${cuerpo.usuarioId} configurado con falla -> 500`);
      return responder(res, 500, { codigo: 'ERROR_INESPERADO' });
    }

    const clave = `${cuerpo.acontecimientoId}|${cuerpo.tipo}|${cuerpo.usuarioId}`;
    const previo = avisos.get(clave);
    if (previo) {
      previo.reentregas += 1;
      console.log(`  duplicado ${clave}`);
      return responder(res, 202, { id: previo.id, estado: 'PENDIENTE', duplicado: true });
    }
    const id = crypto.randomUUID();
    avisos.set(clave, { id, recibidoEn: new Date().toISOString(), reentregas: 0, cuerpo });
    console.log(`  nuevo ${clave} -> "${cuerpo.datos.evento}" para ${cuerpo.usuarioId}`);
    return responder(res, 202, { id, estado: 'PENDIENTE', duplicado: false });
  }

  if (method === 'GET' && ruta === '/_stub/notificaciones') {
    return responder(res, 200, { modo, usuariosConFalla: [...usuariosConFalla], total: avisos.size, avisos: [...avisos.values()] });
  }
  if (method === 'DELETE' && ruta === '/_stub/notificaciones') {
    avisos.clear(); usuariosConFalla.clear();
    console.log('Avisos y usuarios con falla limpiados');
    return responder(res, 204);
  }
  const falla = ruta.match(/^\/_stub\/fallar\/([^/]+)$/);
  if (method === 'PUT' && falla) {
    const u = decodeURIComponent(falla[1]); usuariosConFalla.add(u);
    console.log(`Usuario con falla: ${u}`);
    return responder(res, 200, { usuariosConFalla: [...usuariosConFalla] });
  }
  if (method === 'DELETE' && ruta === '/_stub/fallar') {
    usuariosConFalla.clear();
    return responder(res, 204);
  }
  const cambio = ruta.match(/^\/_stub\/modo\/(ok|error|timeout)$/);
  if (method === 'POST' && cambio) {
    modo = cambio[1];
    console.log(`Modo cambiado a ${modo}`);
    return responder(res, 200, { modo });
  }

  responder(res, 404, { error: `Ruta no simulada: ${method} ${ruta}` });
});

servidor.listen(PORT, () => {
  console.log(`Stub de Notificaciones (MS9) escuchando en http://localhost:${PORT}`);
  console.log(TOKEN === null ? 'SERVICIO_TOKEN no definida: se acepta cualquier X-Service-Token presente' : 'SERVICIO_TOKEN definida: el header debe coincidir');
});
