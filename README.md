# TicketYa - Trabajo Practico Especial

## Interfaz grafica

La interfaz de cliente esta desarrollada con Vue 3 y Vite. Permite buscar eventos, consultar sectores y disponibilidad, reservar lugares, iniciar compras, probar el pago simulado y consultar las entradas emitidas.

### Requisitos

- Node.js 20.19+ o 22.12+.
- API Gateway iniciado en `http://localhost:8086`.
- Para probar todo el flujo, iniciar tambien los microservicios y bases de datos requeridos. Las instrucciones estan en los README de cada servicio dentro de `Backend`.

### Ejecucion local en Windows (PowerShell)

Desde la raiz del repositorio:

```powershell
Set-Location .\Frontend
npm install
npm run dev
```

Abrir `http://localhost:5173` en el navegador. La interfaz consume la API exclusivamente a traves del Gateway. Por defecto usa `http://localhost:8086`; para cambiarlo, configurar `VITE_GATEWAY_URL` en `Frontend\.env.local` y reiniciar Vite. El origen `http://localhost:5173` ya esta habilitado en el CORS del Gateway.

La aplicacion usa la identidad de demostracion `cliente-demo`, editable desde la barra superior. El pago es una simulacion local de MS4 y no realiza cobros reales. Para mas detalles, consultar [Frontend/README.md](Frontend/README.md).