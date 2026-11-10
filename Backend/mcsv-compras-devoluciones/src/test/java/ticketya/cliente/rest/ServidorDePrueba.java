package ticketya.cliente.rest;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Servidor HTTP local para probar los clientes REST: responde lo configurado y guarda el último pedido. */
class ServidorDePrueba implements AutoCloseable {

    private final HttpServer servidor;
    volatile int status = 200;
    volatile String cuerpo = "";
    volatile String metodo;
    volatile String ruta;
    volatile String cuerpoRecibido;

    ServidorDePrueba() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        servidor.createContext("/", intercambio -> {
            metodo = intercambio.getRequestMethod();
            ruta = intercambio.getRequestURI().getPath();
            cuerpoRecibido = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                intercambio.getResponseBody().write(bytes);
            }
            intercambio.close();
        });
        servidor.start();
    }

    String url() {
        return "http://localhost:" + servidor.getAddress().getPort();
    }

    void responder(int status, String cuerpo) {
        this.status = status;
        this.cuerpo = cuerpo;
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}
