# Diseño: interfaz cliente Vue

## Arquitectura

Aplicación SPA con Vue 3, Vite y JavaScript, sin framework de componentes ni cliente HTTP adicional. Un módulo de API centraliza el URL base, la identidad y los errores HTTP. El navegador solo consume paths públicos del Gateway; no conoce los puertos de los microservicios.

## Flujo

1. Buscar eventos con `GET /eventos` y consultar su disponibilidad con `GET /eventos/{eventoId}`.
2. Elegir asientos de `disponibilidad.asientosDisponibles` para sectores `NUMERADO`, o una cantidad para sectores `NO_NUMERADO`.
3. Crear una reserva con `POST /disponibilidad/eventos/{eventoId}/reservas`, enviando `X-User-Id`, `solicitudId` única y los detalles según el tipo de sector.
4. Crear una compra con `POST /compras` usando el `reservaId`.
5. Iniciar el pago con `POST /compras/{compraId}/pago`, `Idempotency-Key` y el medio de pago seleccionado.
6. Consultar entradas con `GET /entradas?page=0&size=50` y permitir refrescar la lista.

La interfaz conserva la identidad de demo en `localStorage`. Una operación de reserva reutiliza su `solicitudId` si se pierde la respuesta y genera otra al cambiar la selección, para recuperar de forma idempotente la reserva original. El pago reutiliza su `Idempotency-Key` ante una respuesta ambigua o error 5xx, y genera una clave nueva después de recibir respuesta HTTP, incluida una decisión de pago rechazada. La selección de asientos se limita a la disponibilidad devuelta por MS1/MS2, pero MS2 permanece como autoridad para validar disponibilidad y atomicidad.

## Manejo de errores

El cliente presenta el código y mensaje del error del Gateway/backend, y diferencia errores de red/CORS. Las acciones de negocio requieren una confirmación explícita de cada paso: reservar, iniciar compra y pagar; el pago avisa que MS4 usa una decisión simulada.

## Verificación

- Instalar dependencias y compilar el bundle de producción con Vite.
- Probar en navegador con Gateway y servicios disponibles: buscar evento, consultar detalle, reservar, comprar, pagar y ver la entrada.
- Comprobar que todas las peticiones de negocio tienen como origen el URL del Gateway configurado.
