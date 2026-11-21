"""Proxy de fallos para pruebas: reenvía al MS7 real y puede perder una respuesta."""
import http.client
import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

lock = threading.Lock()
mode = 'normal'
counts = {'forwarded': 0, 'dropped': 0, 'rejected': 0}

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def send_json(self, status, data):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == '/_test/estado':
            with lock:
                self.send_json(200, {'modo': mode, **counts})
        else:
            self.send_json(404, {})

    def do_PUT(self):
        global mode
        states = {'/_test/normal': 'normal', '/_test/no-disponible': 'no-disponible',
                  '/_test/perder-respuesta': 'perder-respuesta'}
        if self.path not in states:
            return self.send_json(404, {})
        with lock:
            mode = states[self.path]
        self.send_json(200, {'modo': mode})

    def do_POST(self):
        global mode
        if not self.path.startswith('/internal/filas/eventos/') or not self.path.endswith('/consumos'):
            return self.send_json(404, {})
        with lock:
            current = mode
            if current == 'no-disponible':
                counts['rejected'] += 1
            elif current == 'perder-respuesta':
                mode = 'normal'
        if current == 'no-disponible':
            return self.send_json(503, {'error': 'MS7 no disponible en prueba'})
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            chunks = []
            while True:
                n = int(self.rfile.readline().strip().split(b';')[0], 16)
                if not n:
                    while self.rfile.readline().strip():
                        pass
                    break
                chunks.append(self.rfile.read(n)); self.rfile.read(2)
            body = b''.join(chunks)
        else:
            body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        connection = http.client.HTTPConnection('ms7-a', 8088, timeout=10)
        headers = {'Content-Type': self.headers.get('Content-Type', 'application/json')}
        correlation = self.headers.get('X-Correlation-Id')
        if correlation:
            headers['X-Correlation-Id'] = correlation
        try:
            connection.request('POST', self.path, body, headers)
            response = connection.getresponse()
            data = response.read()
            with lock:
                counts['forwarded'] += 1
                if current == 'perder-respuesta' and response.status == 200:
                    counts['dropped'] += 1
            if current == 'perder-respuesta' and response.status == 200:
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            self.send_response(response.status)
            self.send_header('Content-Type', response.getheader('Content-Type', 'application/json'))
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        finally:
            connection.close()

ThreadingHTTPServer(('0.0.0.0', 8082), Handler).serve_forever()
