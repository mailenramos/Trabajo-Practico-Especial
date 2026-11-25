# TicketYa - Interfaz de cliente

Aplicacion de demostracion hecha con Vue 3 y Vite. Permite buscar eventos, elegir lugares, reservar, iniciar una compra, pagar con la simulacion local de MS4 y consultar las entradas emitidas. Todas las operaciones de TicketYa pasan por el API Gateway.

## Requisitos

- Node.js 20.19+ o 22.12+ instalado. `npm` se instala junto con Node.js; `npm ci` descarga las dependencias del proyecto, no instala Node.js.
- API Gateway en `http://localhost:8086`, MS6 (Usuarios) para iniciar sesión y los microservicios requeridos por el flujo encendidos.

En Windows, abre PowerShell desde esta carpeta. La primera vez que se descarga el proyecto, instala las dependencias:

```powershell
npm ci
```

Luego inicia el servidor local:

```powershell
npm run dev
```

Deja la terminal abierta y abre la URL que informa Vite (por defecto `http://localhost:5173`). En ejecuciones posteriores no hace falta volver a ejecutar `npm ci`; alcanza con `npm run dev`. El Gateway ya permite ese origen para CORS.

Para cambiar la URL del Gateway, crear `.env.local` desde `.env.example` y ajustar `VITE_GATEWAY_URL`, o definir esa variable antes de iniciar Vite. Reiniciar el servidor de desarrollo luego de cambiarla.

## Sesión (Entrega 2: JWT)

La aplicación arranca en la pantalla de acceso:

- **Iniciar sesión** llama a `POST /auth/login` (MS6) y guarda el JWT recibido.
- **Crear cuenta** llama a `POST /auth/registro`; la cuenta queda como `CLIENTE` y se inicia sesión automáticamente.
- **Usuarios de prueba:** los botones completan el correo y la contraseña (`Prueba123`) de los usuarios que MS6 crea en desarrollo (`cliente@`, `organizador@`, `admin@` y `personal@ticketya.test`).

Cada pedido envía `Authorization: Bearer <token>`. La aplicación ya no envía `X-User-Id` ni `X-User-Role`: el Gateway valida el token y propaga la identidad a los microservicios.

- El token se guarda en `localStorage` (clave `ticketya.sesion`), así la sesión sobrevive a una recarga. Para una demo académica es suficiente; en producción convendría una cookie `HttpOnly` para reducir el riesgo ante XSS.
- La sesión se cierra al presionar **Cerrar sesión**, cuando vence el token (una hora por defecto) o si el Gateway responde `401`. En esos casos se vuelve al login con un aviso.
- La barra superior muestra el nombre (de `GET /usuarios/me`) y los roles del token.
- Según los roles: comprar requiere `CLIENTE`, y **Mis entradas** se muestra para `CLIENTE` o `ADMIN`. **Mis compras** se muestra solo para `CLIENTE`. Los demás roles pueden explorar eventos.

La consulta pública de eventos no exige token en el Gateway, pero mientras MS1 siga exigiendo encabezados de identidad, la aplicación pide iniciar sesión antes de buscar.

El código de la sesión está en `src/session.js` (estado, decodificación del token y vencimiento), `src/api.js` (envío del token y manejo del 401) y `src/LoginView.vue` (pantalla de acceso).

## Flujo de prueba

1. Iniciar sesión, por ejemplo con el usuario de prueba **Cliente**.
2. Buscar un evento publicado y abrirlo para consultar sectores y disponibilidad.
3. Elegir asientos disponibles de un sector numerado o una cantidad de un sector sin numerar y crear la reserva.
4. Iniciar la compra usando esa reserva.
5. Confirmar el pago simulado; MS4 no realiza un cobro real.
6. Consultar las entradas emitidas desde la seccion **Mis entradas**.
7. Revisar el historial en **Mis compras** (`src/MisCompras.vue`) y pedir la devolución de una entrada.

### Mis compras y devoluciones

La pantalla usa `GET /compras`, `GET /devoluciones` y `POST /devoluciones` (MS3 por el Gateway). Cada compra muestra su estado, total y lugares. La devolución es **por entrada**: el botón **Pedir devolución** pide una confirmación con el monto y envía `{ entradaId }`. El estado de cada entrada se ve en la misma fila: pendiente de revisión, aprobada (con su reintegro) o rechazada con el motivo, y una rechazada se puede pedir de nuevo. La devolución se acepta hasta 72 horas antes del evento; la pantalla deshabilita el botón fuera de plazo, pero MS3 valida todas las reglas y su mensaje de error se muestra tal cual. Aprobar o rechazar es una tarea del administrador y todavía no tiene pantalla.

La reserva tiene una vigencia limitada. La compra y el pago son acciones separadas y solo se ejecutan al presionar sus botones.

## Build

```powershell
npm run build
```
