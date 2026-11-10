# Tasks

## 1. Preparar la configuración acordada

- [x] 1.1 Registrar los acuerdos: conservar paths sin `/api/v1`, puerto del Gateway `8086`, propagar los headers de identidad disponibles sin autenticar y excluir las rutas internas.
- [x] 1.2 Contrastar la allowlist propuesta con los contratos públicos vigentes de MS1, MS2, MS3 y MS5; excluir MS4 Pagos del Gateway.
- [x] 1.3 Proponer `http://localhost:5173` como origen CORS inicial, configurable para ajustarlo al frontend cuando se elija su herramienta.

## 2. Crear la aplicación Gateway

- [x] 2.1 Crear el build ejecutable de `Backend/APIGateWay` con Spring Boot y Spring Cloud Gateway; seleccionar versiones Java/Spring compatibles y definir configuración externalizada del puerto, URLs de MS1, MS2, MS3 y MS5, y CORS. Verificar compilación.
- [x] 2.2 Configurar las rutas allowlisted para Eventos/Lugares, Reservas, Compras/Devoluciones y Entradas, conservando paths y métodos backend. No crear un proxy genérico ni ruta para Pagos. Verificar con pruebas de configuración.
- [x] 2.3 Configurar CORS para los orígenes acordados y los métodos/headers usados por el frontend, incluidos `X-User-Id`, `X-User-Role` e `Idempotency-Key`. Verificar preflight de origen permitido y no permitido.
- [x] 2.4 Asegurar que `X-User-Id` y `X-User-Role` se retransmitan cuando existan, sin autenticación ni valores de identidad por defecto. Verificar también que la ausencia de headers no se convierta en identidad generada por el Gateway.

## 3. Probar allowlist y aislamiento de rutas

- [x] 3.1 Agregar pruebas con destinos HTTP falsos para verificar método, path, query, body y headers de solicitudes públicas de cada servicio. Verificar status y cuerpo de respuesta preservados.
- [x] 3.2 Agregar pruebas negativas que confirmen que `/internal/**`, los callbacks de Eventos/Reservas/Entradas, `POST /eventos-cancelados`, rutas de Pagos, rutas desconocidas y métodos no permitidos no alcanzan ningún backend.
- [x] 3.3 Agregar una prueba de destino no disponible y verificar respuesta 503; no modificar errores de negocio que sí responda un backend.

## 4. Documentar y verificar ejecución local

- [x] 4.1 Documentar requisitos, comando de inicio, puerto, variables de configuración, tabla final de rutas públicas y limitación de identidad sin autenticación.
- [x] 4.2 Ejecutar las pruebas del Gateway contra destinos HTTP falsos y verificar rutas, CORS, exclusiones y fallos de conexión.
- [x] 4.3 Verificar manualmente una consulta pública y la creación de una reserva con los microservicios locales en ejecución.
- [x] 4.4 Verificar que no se alteraron contratos ni builds de los microservicios y que la allowlist coincide con los documentos OpenAPI/contratos vigentes.
