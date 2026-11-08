# Gestión de lugares, sectores y eventos

Microservicio Spring Boot de TicketYa. Gestiona lugares físicos, sus sectores y el ciclo de vida de eventos. Persiste en MongoDB en colecciones propias; la comunicación entre servicios usa IDs y llamadas directas.

## Requisitos

- JDK 21 o superior.
- Maven 3.9 o superior.
- Docker Desktop con Docker Compose para MongoDB y las pruebas de integración.
- Node.js (opcional, solo para simular Reservas desde Postman).

## Levantar MongoDB y el servicio

Ejecutá estos pasos desde `GestionLugaresFisicos`.

1. Iniciá MongoDB:

   ```sh
   docker compose up -d mongodb
   ```

   MongoDB queda disponible en `localhost:27017`; Compose conserva los datos en `mongodb_data`.

2. Iniciá el servicio:

   ```sh
   mvn spring-boot:run
   ```

   Por defecto, MongoDB usa `mongodb://localhost:27017/ticketya_lugares` y el servicio escucha en `http://localhost:8081`. El perfil predeterminado es `dev` y carga datos de ejemplo.

3. Consultá lugares con headers de identidad:

   ```sh
   curl.exe -H "X-User-Id: organizador-demo" -H "X-User-Role: organizador" http://localhost:8081/lugares
   ```

4. La documentación interactiva está en <http://localhost:8081/swagger-ui/index.html> y el documento OpenAPI en <http://localhost:8081/v3/api-docs>.

Para detener MongoDB sin borrar sus datos:

```sh
docker compose down
```

## Endpoints

El servicio local usa `http://localhost:8081` por defecto. Todas las rutas requieren `X-User-Id` junto con el rol indicado, salvo el callback interno de disponibilidad. Los valores canónicos de rol son `administrador` y `organizador`; el código recorta espacios externos y compara sin distinguir mayúsculas.

| Método | Ruta | Rol requerido | Descripción |
|---|---|---|---|
| POST | `/lugares` | `administrador` | Registra un lugar. |
| GET | `/lugares` | `organizador` o `administrador` | Lista lugares. |
| GET | `/lugares/{id}` | `organizador` o `administrador` | Consulta un lugar y sus sectores. |
| GET | `/lugares/{id}/sectores` | `organizador` o `administrador` | Lista los sectores de un lugar. |
| POST | `/lugares/{id}/sectores` | `administrador` | Agrega un sector numerado o no numerado. |
| POST | `/eventos` | `organizador` | Crea un evento en borrador. |
| PUT | `/eventos/{eventoId}/sectores/{sectorId}/precio` | Organizador dueño | Define el precio de un sector del evento en borrador. |
| PUT | `/eventos/{eventoId}/limite-entradas-por-cliente` | Organizador dueño | Actualiza el límite por cliente durante el borrador. |
| PUT | `/eventos/{eventoId}/publicar` | Organizador dueño | Inicializa la disponibilidad con Reservas y publica el evento. |
| PUT | `/eventos/{eventoId}/venta/suspender` | `administrador` | Suspende la venta sin crear un estado formal nuevo. |
| PUT | `/eventos/{eventoId}/venta/reactivar` | `administrador` | Reactiva la venta. |
| PUT | `/eventos/{eventoId}/cancelar` | `administrador` | Cancela el evento después de confirmarlo con Reservas. |
| PUT | `/eventos/{eventoId}/disponibilidad` | Interno: Reservas | Recibe el aviso de agotado o recuperación. No exige `X-User-Id` ni `X-User-Role`; no debe exponerse por el API Gateway. |

Las rutas de gestión de eventos y lugares devuelven errores controlados con `timestamp`, `status`, `error` y `mensaje`; `campos` se incluye como lista de `{campo, mensaje}` cuando corresponde.

## Integración con otros servicios

La comunicación entre servicios en la Entrega 1 es directa, por REST/JSON, y no usa autenticación entre servicios. El Gateway propaga los headers de usuario solo para las rutas públicas.

El documento [docs/api-gateway-contract.md](docs/api-gateway-contract.md) detalla los paths, headers y reglas de reenvío para el API Gateway.

### Reservas — contrato confirmado

URL base: propiedad `reservas.base-url`, predeterminada en `http://localhost:8080`. Timeouts: `reservas.connect-timeout` (2 s) y `reservas.read-timeout` (5 s).

Este servicio hace estas llamadas a Reservas:

1. **Inicializar disponibilidad al publicar:** `PUT /disponibilidad/eventos/{eventoId}`. Envía `limiteEntradasPorCliente` y los sectores; para sectores numerados deriva los pares `{fila, numero}` y su `capacidadTotal` a partir de filas × asientosPorFila. La respuesta esperada es `204`; repetir la inicialización con los mismos datos es idempotente.
2. **Cambiar el estado de venta:** `PUT /disponibilidad/eventos/{eventoId}/venta`, con `{"estado":"ACTIVA"}`, `SUSPENDIDA`, `CANCELADA` o `FINALIZADA`. La respuesta esperada es `204`.
3. **Consultar disponibilidad:** el contrato `GET /disponibilidad/eventos/{eventoId}` está documentado para el detalle futuro de F3, pero esa llamada todavía no está implementada en este servicio.

Reservas llama a este servicio mediante el callback `PUT /eventos/{eventoId}/disponibilidad`, con `{"agotado":true}` o `{"agotado":false}`. Responde `204` para cambios, avisos duplicados y avisos tardíos de eventos cancelados o finalizados; `404` si el evento no existe y `409` si sigue en borrador. El callback es interno, sin headers de usuario y fuera del Gateway.

Si Reservas falla, no responde o no confirma con `204`, publicar responde `503` y el evento queda en borrador. Si falla al suspender, reactivar o cancelar, el servicio responde `503` y no cambia el estado local; la operación se puede reintentar.

### Compras — contrato pendiente de confirmación

La integración con Compras y Devoluciones está pendiente de confirmación y todavía no está implementada.

## Puertos

Este servicio usa el puerto `8081` por defecto (`PORT` permite sobrescribirlo). Reservas usa `8080` en el entorno local; mantené ambos puertos separados.

## Variables de entorno y comandos

`application.yml` y `ReservasProperties` definen estos nombres y defaults:

| Variable de entorno | Propiedad | Default |
|---|---|---|
| `PORT` | `server.port` | `8081` |
| `MONGODB_URI` | `spring.data.mongodb.uri` | `mongodb://localhost:27017/ticketya_lugares` |
| `RESERVAS_BASE_URL` | `reservas.base-url` | `http://localhost:8080` |
| `RESERVAS_CONNECT_TIMEOUT` | `reservas.connect-timeout` | `2s` |
| `RESERVAS_READ_TIMEOUT` | `reservas.read-timeout` | `5s` |
| `EVENTOS_FINALIZACION_INTERVAL` | `eventos.finalizacion.interval` | `60s` |
| `SPRING_PROFILES_ACTIVE` | Perfil activo de Spring | `dev` (perfil predeterminado) |

Ejemplos para configurar variables e iniciar el servicio desde la carpeta `GestionLugaresFisicos`:

**PowerShell**

```powershell
$env:PORT = "8081"
$env:MONGODB_URI = "mongodb://localhost:27017/ticketya_lugares"
$env:RESERVAS_BASE_URL = "http://localhost:8080"
$env:RESERVAS_CONNECT_TIMEOUT = "2s"
$env:RESERVAS_READ_TIMEOUT = "5s"
$env:EVENTOS_FINALIZACION_INTERVAL = "60s"
$env:SPRING_PROFILES_ACTIVE = "dev"
mvn spring-boot:run
```

**cmd**

```bat
set PORT=8081
set MONGODB_URI=mongodb://localhost:27017/ticketya_lugares
set RESERVAS_BASE_URL=http://localhost:8080
set RESERVAS_CONNECT_TIMEOUT=2s
set RESERVAS_READ_TIMEOUT=5s
set EVENTOS_FINALIZACION_INTERVAL=60s
set SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

**Git Bash**

```bash
export PORT=8081
export MONGODB_URI=mongodb://localhost:27017/ticketya_lugares
export RESERVAS_BASE_URL=http://localhost:8080
export RESERVAS_CONNECT_TIMEOUT=2s
export RESERVAS_READ_TIMEOUT=5s
export EVENTOS_FINALIZACION_INTERVAL=60s
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

## Pruebas con Postman

En `postman/` están las dos colecciones:

- `TicketYa-Lugares.postman_collection.json`: pruebas de lugares y sectores.
- `TicketYa-Eventos.postman_collection.json`: pruebas de eventos e integración con Reservas.

Para el flujo de eventos, ejecutá primero la carpeta **00 Preparación**. La carpeta **05 Publicar y gestión: validaciones previas** funciona con Reservas encendido o apagado. Después elegí uno de estos escenarios y corrélos por separado:

- **06 [Reservas ENCENDIDO]** necesita Reservas respondiendo en `localhost:8080`.
- **07 [Reservas APAGADO]** requiere que no haya ningún proceso escuchando en el puerto `8080`.

Para simular Reservas en local, desde la carpeta `GestionLugaresFisicos` ejecutá en otra terminal:

```sh
node postman/reservas-stub.js
```

El stub escucha en el puerto `8080` por defecto (`PORT` permite cambiarlo), imprime los pedidos recibidos y valida el formato de las rutas y los cuerpos de Reservas. Requiere Node.js.

## Ejecutar las pruebas automatizadas

Desde `GestionLugaresFisicos`:

```sh
mvn test
```

Las pruebas de integración de MongoDB usan Testcontainers y requieren Docker iniciado.

## Modelo de datos

Los lugares están en la colección `lugares`, con sectores embebidos. Los asientos no se persisten: en sectores numerados se derivan de `filas` × `asientosPorFila` y se identifican como `(sectorId, fila, numero)`.

Los eventos están en la colección `eventos`. Copian la ciudad y la configuración/nombre de los sectores del lugar; precios y `precioMinimo` usan `BigDecimal`/Decimal128. En la respuesta JSON, los estados se serializan en minúsculas: `borrador`, `publicado`, `agotado`, `cancelado` y `finalizado`. `ventaSuspendida` es un indicador independiente. El límite de entradas por cliente es entero y vale `4` por defecto. `fechaHora` es la fecha y hora de inicio; al pasarla, el proceso automático finaliza el evento.

```mermaid
classDiagram
    direction LR

    class LugarDocumento {
        <<document>>
        String id (ObjectId hexadecimal)
        String nombre
        String direccion
        String ciudad
        double latitud
        double longitud
        List~SectorEmbebido~ sectores
        Long version
    }

    class SectorEmbebido {
        <<embedded>>
        String id (UUID)
        String nombre
        TipoSector tipo
        Integer filas
        Integer asientosPorFila
        Integer capacidad
    }

    LugarDocumento "1" *-- "0..*" SectorEmbebido : sectores[]

    class EventoDocumento {
        <<document>>
        String id (ObjectId hexadecimal)
        String organizadorId
        String lugarId
        String ciudad
        String nombre
        String descripcion
        String tipo
        List~String~ participantes
        Instant fechaHora
        EstadoEvento estado
        boolean ventaSuspendida
        int limiteEntradasPorCliente
        List~SectorEventoEmbebido~ sectores
        BigDecimal precioMinimo
        Long version
    }

    class SectorEventoEmbebido {
        <<embedded>>
        String sectorId
        String nombre
        TipoSector tipo
        Integer filas
        Integer asientosPorFila
        Integer capacidad
        BigDecimal precio
    }

    EventoDocumento "1" *-- "0..*" SectorEventoEmbebido : sectores[]
    EventoDocumento ..> LugarDocumento : lugarId, referencia por ID

    note for LugarDocumento "Colección MongoDB: lugares. El ID se expone como String hexadecimal."
    note for SectorEmbebido "id es UUID String. Los asientos no se guardan como documentos."
    note for EventoDocumento "Colección MongoDB: eventos. Estados JSON: borrador, publicado, agotado, cancelado, finalizado."
```
