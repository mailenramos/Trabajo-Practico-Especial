package ticketya.cliente.rest;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Doble HTTP local de Gestión de usuarios: responde lo configurado y guarda el último pedido. */
class ServidorDePrueba implements AutoCloseable {

    private final HttpServer servidor;
    final AtomicInteger pedidos = new AtomicInteger();
    volatile int status = 200;
    volatile String cuerpo = "";
    volatile long demoraMs;
    volatile String ruta;
    volatile Headers encabezados;

    ServidorDePrueba() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        servidor.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        servidor.createContext("/", intercambio -> {
            pedidos.incrementAndGet();
            ruta = intercambio.getRequestURI().getPath();
            encabezados = intercambio.getRequestHeaders();
            try {
                if (demoraMs > 0) {
                    Thread.sleep(demoraMs);
                }
                byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
                intercambio.getResponseHeaders().add("Content-Type", "application/json");
                intercambio.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    intercambio.getResponseBody().write(bytes);
                }
            } catch (InterruptedException | IOException ignorada) {
                // El cliente cortó por timeout
            } finally {
                intercambio.close();
            }
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
