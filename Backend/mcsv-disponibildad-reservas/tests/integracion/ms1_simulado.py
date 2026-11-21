"""MS1 de prueba para la integración aislada; no usar como servicio real."""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

fallar_consultas = False

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def responder(self, codigo, body=None):
        data = json.dumps(body).encode() if body is not None else b""
        self.send_response(codigo)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if fallar_consultas and self.path.startswith("/internal/eventos/"):
            return self.responder(503, {"error": "indisponible"})
        partes = self.path.split("/")
        if len(partes) != 4 or partes[:3] != ["", "internal", "eventos"]:
            return self.responder(404)
        evento = partes[3]
        self.responder(200, {"eventoId": evento, "organizadorId": "organizador-prueba",
                             "estado": "publicado", "requiereFila": not evento.startswith("sin-fila-")})

    def do_PUT(self):
        global fallar_consultas
        if self.path in ("/_test/falla/activar", "/_test/falla/desactivar"):
            fallar_consultas = self.path.endswith("activar") and not self.path.endswith("desactivar")
            return self.responder(204)
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            while True:
                size = int(self.rfile.readline().strip().split(b';')[0], 16)
                if not size:
                    while self.rfile.readline().strip():
                        pass
                    break
                self.rfile.read(size)
                self.rfile.read(2)
        else:
            self.rfile.read(int(self.headers.get('Content-Length', '0')))
        # Solo se confirma el contrato requerido por MS7 y el outbox de MS2.
        if self.path.startswith("/internal/eventos/") and self.path.endswith("/politica-fila"):
            return self.responder(204)
        if self.path.startswith("/eventos/") and self.path.endswith("/disponibilidad"):
            return self.responder(204)
        self.responder(404)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
