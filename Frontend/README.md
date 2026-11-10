# TicketYa - Interfaz de cliente

Aplicacion de demostracion hecha con Vue 3 y Vite. Permite buscar eventos, elegir lugares, reservar, iniciar una compra, pagar con la simulacion local de MS4 y consultar las entradas emitidas. Todas las llamadas de negocio pasan por el API Gateway.

## Requisitos

- Node.js 20.19+ o 22.12+.
- API Gateway en `http://localhost:8086` y los microservicios requeridos por el flujo encendidos.

Desde esta carpeta, en PowerShell:

```powershell
npm install
npm run dev
```

Abrir la URL que informa Vite (por defecto `http://localhost:5173`). El Gateway ya permite ese origen para CORS.

Para cambiar la URL del Gateway, crear `.env.local` desde `.env.example` y ajustar `VITE_GATEWAY_URL`, o definir esa variable antes de iniciar Vite. Reiniciar el servidor de desarrollo luego de cambiarla.

La identidad de Entrega 1 no usa autenticacion. La interfaz envia `X-User-Id` y `X-User-Role: CLIENTE`; el identificador de demo se puede editar en la barra superior y queda guardado en el navegador.

## Flujo de prueba

1. Buscar un evento publicado y abrirlo para consultar sectores y disponibilidad.
2. Elegir asientos disponibles de un sector numerado o una cantidad de un sector sin numerar y crear la reserva.
3. Iniciar la compra usando esa reserva.
4. Confirmar el pago simulado; MS4 no realiza un cobro real.
5. Consultar las entradas emitidas desde la seccion **Mis entradas**.

La reserva tiene una vigencia limitada. La compra y el pago son acciones separadas y solo se ejecutan al presionar sus botones.

## Build

```powershell
npm run build
```
