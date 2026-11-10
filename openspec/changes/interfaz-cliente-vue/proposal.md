# Propuesta: interfaz cliente con Vue

## Motivo

El sistema ya cuenta con un API Gateway funcional y microservicios para eventos, reservas, compras, pagos y entradas, pero no ofrece una interfaz gráfica para recorrer el flujo principal de un cliente.

## Alcance

Crear en `Frontend` una aplicación web mínima con Vue 3 y Vite que permita buscar eventos publicados, consultar sectores y disponibilidad, elegir asientos o cantidad, reservar, iniciar una compra, simular el pago y consultar las entradas emitidas. Todas las llamadas de negocio deben pasar por el API Gateway.

La identidad de Entrega 1 será un identificador de cliente configurable en la interfaz y se enviará mediante los encabezados acordados. El pago es una simulación local de MS4, no un cobro real.

## Fuera de alcance

- Autenticación, registro de usuarios, perfiles o permisos administrativos.
- Gestión de lugares, creación/publicación de eventos, devoluciones y reintegros.
- Cambios a los contratos o al código de los microservicios y del Gateway.
- Despliegue productivo, diseño de asientos gráfico a escala o procesamiento real de pagos.

## Dependencias

- API Gateway en `http://localhost:8086`, configurable mediante `VITE_GATEWAY_URL`.
- MS1 para búsqueda y detalle de eventos; MS2 para disponibilidad y reservas; MS3 para compras y coordinación del pago; MS4 para la simulación local de pagos; MS5 para consultar entradas.
- CORS del Gateway habilitando el origen usado por Vite, por defecto `http://localhost:5173`.
