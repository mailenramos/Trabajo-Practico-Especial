// Simulador de Fila virtual (MS7) para probar el aviso de cancelación de MS1.
// Uso: node postman/fila-stub.js   (puerto 8088 por defecto; PORT lo cambia)
//
// PUT /internal/filas/eventos/{eventoId}/cancelacion     sin body
//   Responde 204 siempre (con fila, sin fila o al repetir).
//   Registra la llamada: eventoId, headers X-Aviso-Id, X-Service-Token (presente/ausente) y X-Correlation-Id.
//   Si SERVICIO_TOKEN está definida, el token se valida (403 si no coincide); si no, se acepta cualquiera.
//
// Diagnóstico y simulación de fallos:
//   GET    /_stub/cancelaciones            lista las llamadas recibidas
//   DELETE /_stub/cancelaciones            limpia la lista
//   POST   /_stub/modo/{ok|error|timeout|invalido}
//          error -> 500; timeout -> no responde; invalido -> 400 (error definitivo, va a ERROR_FINAL)

const http = require('http');

const PORT = process.env.PORT || 8088;
const TOKEN = process.env.SERVICIO_TOKEN || null;
const RUTA = /^\/internal\/filas\/eventos\/([^/]+)\/cancelacion$/;

const llamadas = [];
let modo = 'ok';

function responder(res, status, cuerpo) {
  if (cuerpo === undefined) { res.writeHead(status); res.end(); return; }
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(cuerpo));
}

const servidor = http.createServer((req, res) => {
  let datos = '';
  req.on('data', (p) => (datos += p));
  req.on('end', () => {
    const ruta = req.url.split('?')[0];
    const m = ruta.match(RUTA);

    if (req.method === 'PUT' && m) {
      const registro = {
        recibidoEn: new Date().toISOString(),
        eventoId: m[1],
        avisoId: req.headers['x-aviso-id'] || null,
        token: req.headers['x-service-token'] ? 'presente' : 'ausente',
        correlationId: req.headers['x-correlation-id'] || null,
        body: datos.length ? datos : null,
        modo,
      };
      llamadas.push(registro);
      console.log(`[${registro.recibidoEn}] PUT ${ruta} aviso=${registro.avisoId} token=${registro.token} corr=${registro.correlationId} body=${registro.body ? 'SÍ (inesperado)' : 'no'} modo=${modo}`);

      if (TOKEN !== null && req.headers['x-service-token'] !== TOKEN) {
        return responder(res, 403, { codigo: 'ACCESO_DENEGADO', mensaje: 'X-Service-Token inválido' });
      }
      if (modo === 'timeout') return;
      if (modo === 'error') return responder(res, 500, { codigo: 'ERROR_INESPERADO' });
      if (modo === 'invalido') return responder(res, 400, { codigo: 'VALIDACION', mensaje: 'Solicitud inválida (simulada)' });
      return responder(res, 204);
    }

    if (ruta === '/_stub/cancelaciones') {
      if (req.method === 'GET') return responder(res, 200, llamadas);
      if (req.method === 'DELETE') { llamadas.length = 0; return responder(res, 204); }
    }

    const mm = ruta.match(/^\/_stub\/modo\/(ok|error|timeout|invalido)$/);
    if (req.method === 'POST' && mm) { modo = mm[1]; return responder(res, 200, { modo }); }

    responder(res, 404, { mensaje: 'Ruta no encontrada en el stub' });
  });
});

servidor.listen(PORT, () => console.log(`Stub de Fila virtual (MS7) escuchando en el puerto ${PORT}${TOKEN ? ' (token exigido)' : ''}`));
