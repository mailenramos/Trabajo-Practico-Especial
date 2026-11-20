// Simulador de Usuarios (MS6) para probar la asignación de personal de acceso en MS1.
// Uso: node postman/usuarios-stub.js   (puerto 8087 por defecto; PORT lo cambia)
//
// GET /internal/usuarios/por-correo?correo={correo}
//   200 { id, roles[], activo }       roles ordenados alfabéticamente; también devuelve usuarios inactivos
//   404 { timestamp, status, error: "USUARIO_NO_ENCONTRADO", mensaje }
//   400 si falta el parámetro correo
//   Si SERVICIO_TOKEN está definida, X-Service-Token debe coincidir (403 si no). Si no, se acepta cualquiera.
//   La búsqueda ignora mayúsculas y espacios alrededor del correo.
//
// Usuarios precargados (el id del primero es el del ejemplo de MS6):
//   personal@ticketya.com    PERSONAL_ACCESO            activo
//   personal2@ticketya.com   PERSONAL_ACCESO            activo
//   cliente@ticketya.com     CLIENTE                    activo   (400 al asignar: sin rol)
//   inactivo@ticketya.com    PERSONAL_ACCESO            inactivo (400 al asignar)
//   organizador@ticketya.com ORGANIZADOR                activo
//   admin@ticketya.com       ADMIN, PERSONAL_ACCESO     activo
//   cualquier otro correo -> 404
//
// Diagnóstico y simulación de fallos:
//   GET    /_stub/usuarios                lista los usuarios
//   PUT    /_stub/usuarios                agrega o reemplaza uno: {"correo","id","roles":[...],"activo":true}
//   GET    /_stub/consultas               consultas recibidas (token, X-Correlation-Id)
//   DELETE /_stub/consultas               limpia las consultas
//   POST   /_stub/modo/{ok|error|timeout} error responde 500; timeout no responde

const http = require('http');
const crypto = require('crypto');

const PORT = process.env.PORT || 8087;
const TOKEN = process.env.SERVICIO_TOKEN || null;
const RUTA = '/internal/usuarios/por-correo';

const usuarios = new Map();
function cargarUsuario(correo, id, roles, activo) {
  usuarios.set(correo.trim().toLowerCase(), { correo, id, roles: [...roles].sort(), activo });
}
function usuariosIniciales() {
  usuarios.clear();
  cargarUsuario('personal@ticketya.com', '00000000-0000-4000-8000-000000000004', ['PERSONAL_ACCESO'], true);
  cargarUsuario('personal2@ticketya.com', '00000000-0000-4000-8000-000000000005', ['PERSONAL_ACCESO'], true);
  cargarUsuario('cliente@ticketya.com', '00000000-0000-4000-8000-000000000001', ['CLIENTE'], true);
  cargarUsuario('inactivo@ticketya.com', '00000000-0000-4000-8000-000000000006', ['PERSONAL_ACCESO'], false);
  cargarUsuario('organizador@ticketya.com', '00000000-0000-4000-8000-000000000002', ['ORGANIZADOR'], true);
  cargarUsuario('admin@ticketya.com', '00000000-0000-4000-8000-000000000003', ['ADMIN', 'PERSONAL_ACCESO'], true);
}
usuariosIniciales();

const consultas = [];
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
  if (TOKEN === null) return true;
  return req.headers['x-service-token'] === TOKEN;
}

const servidor = http.createServer(async (req, res) => {
  const { method } = req;
  const [ruta, query = ''] = req.url.split('?');

  if (method === 'GET' && ruta === RUTA) {
    const params = new URLSearchParams(query);
    const correo = params.get('correo');
    consultas.push({
      recibidoEn: new Date().toISOString(),
      correo,
      token: req.headers['x-service-token'] ? 'presente' : 'ausente',
      correlationId: req.headers['x-correlation-id'] || null,
    });
    console.log(`[${new Date().toISOString()}] GET ${RUTA} correo=${correo} token=${req.headers['x-service-token'] ? 'sí' : 'NO'} corr=${req.headers['x-correlation-id'] || '-'} modo=${modo}`);

    if (!tokenValido(req)) return responder(res, 403, { codigo: 'ACCESO_DENEGADO', mensaje: 'X-Service-Token ausente o inválido' });
    if (modo === 'timeout') return;
    if (modo === 'error') return responder(res, 500, { error: 'ERROR_INESPERADO' });
    if (!correo || correo.trim() === '') {
      return responder(res, 400, { timestamp: new Date().toISOString(), status: 400, error: 'VALIDACION', mensaje: 'El parámetro correo es obligatorio' });
    }
    const u = usuarios.get(correo.trim().toLowerCase());
    if (!u) {
      return responder(res, 404, { timestamp: new Date().toISOString(), status: 404, error: 'USUARIO_NO_ENCONTRADO', mensaje: 'No existe un usuario con ese correo' });
    }
    return responder(res, 200, { id: u.id, roles: u.roles, activo: u.activo });
  }

  if (ruta === '/_stub/usuarios') {
    if (method === 'GET') return responder(res, 200, [...usuarios.values()]);
    if (method === 'PUT') {
      let c;
      try { c = JSON.parse(await leerCuerpo(req)); } catch (e) { return responder(res, 400, { mensaje: 'JSON inválido' }); }
      if (typeof c.correo !== 'string' || !c.correo.trim() || typeof c.id !== 'string' || !Array.isArray(c.roles)) {
        return responder(res, 400, { mensaje: 'Se esperan correo, id (texto) y roles (lista)' });
      }
      cargarUsuario(c.correo, c.id, c.roles, c.activo !== false);
      return responder(res, 200, usuarios.get(c.correo.trim().toLowerCase()));
    }
    if (method === 'DELETE') { usuariosIniciales(); return responder(res, 204); }
  }

  if (ruta === '/_stub/consultas') {
    if (method === 'GET') return responder(res, 200, consultas);
    if (method === 'DELETE') { consultas.length = 0; return responder(res, 204); }
  }

  const m = ruta.match(/^\/_stub\/modo\/(ok|error|timeout)$/);
  if (method === 'POST' && m) { modo = m[1]; return responder(res, 200, { modo }); }

  responder(res, 404, { mensaje: 'Ruta no encontrada en el stub' });
});

servidor.listen(PORT, () => console.log(`Stub de Usuarios (MS6) escuchando en el puerto ${PORT}${TOKEN ? ' (token exigido)' : ''}`));
