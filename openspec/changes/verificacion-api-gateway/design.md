## Contexto

El Gateway está implementado en `Backend/APIGateWay`, escucha en `8086`, conserva las rutas backend y mantiene rutas explícitas para MS1, MS2, MS3 y MS5. Su suite actual se ejecuta con Spring Boot en un puerto aleatorio y servidores HTTP falsos; cubre reenvío, allowlist, CORS y un destino no disponible. Es una prueba de integración del Gateway, no una prueba unitaria aislada.

El equipo necesita dos evidencias distintas:

1. Una suite automatizada, rápida y repetible que compruebe el comportamiento del Gateway sin dependencias externas.
2. Una colección Postman para comprobar la integración real con los microservicios locales y facilitar la demostración del flujo.

Los valores de identidad de Entrega 1 (`X-User-Id` y `X-User-Role`) son headers de prueba, no credenciales ni autenticación. Postman puede enviarlos, pero no verifica la seguridad de identidad.

## Diseño de pruebas automatizadas

### Pruebas unitarias

- Probar aisladamente la conversión de errores de conectividad del `GatewayUnavailableFilter` a HTTP 503 y su cuerpo de error.
- Probar la configuración CORS y sus decisiones de origen/método/headers permitidos, sin afirmar que Postman aplica CORS.
- Mantener estas pruebas independientes de sockets de servicios reales y de bases de datos.

### Pruebas de integración del Gateway

- Arrancar la aplicación en puerto aleatorio y dirigir sus destinos a backends falsos locales.
- Verificar cada ruta pública y método registrado contra el backend dueño, incluyendo que no se invoquen los otros backends.
- Verificar preservación de path, query, método, body, `Content-Type`, headers de identidad y `Idempotency-Key`, así como status, headers y cuerpo de respuesta del backend.
- Verificar rutas internas, rutas desconocidas y métodos no permitidos: no deben generar solicitudes a ningún backend.
- Simular destino caído y comprobar HTTP 503 sin respuesta de éxito ficticia.
- Mantener la prueba Maven ejecutable sin MS1–MS5, MongoDB, MySQL ni Docker.

La suite debe corresponder a las rutas documentadas vigentes. No debe inferir rutas nuevas ni relajar una prueba para aceptar el enrutamiento de un endpoint interno.

## Diseño de pruebas manuales con Postman

Agregar una colección importable y un environment local con variables no secretas:

- `gatewayUrl` con valor predeterminado `http://localhost:8086`.
- `userId`, `userRole` y los IDs de lugar, sector, evento, reserva, compra y entrada requeridos por los requests.
- Las variables de recursos generados se capturan desde respuestas exitosas cuando el contrato y el formato de respuesta lo permitan; si un servicio requiere IDs preexistentes, la documentación lo indica.

Organizar requests por escenarios:

1. **Conectividad y consulta pública:** buscar eventos y consultar lugar/evento por Gateway.
2. **Identidad y validaciones:** repetir una operación con headers de cliente/organizador y sin identidad, verificando la respuesta del servicio, no una identidad inventada por el Gateway.
3. **Reserva:** crear una reserva de un evento publicado con disponibilidad y guardar el identificador de reserva si la respuesta lo devuelve.
4. **Compra y entradas:** iniciar una compra desde una reserva, pagar usando una `Idempotency-Key` nueva por ejecución y consultar la compra/entradas si el flujo queda exitoso. Este escenario es opcional cuando no están disponibles todos los servicios dependientes o no hay datos válidos.
5. **Rutas no publicadas:** probar callback/eventos internos y métodos no permitidos; esperar una respuesta de ruta no disponible y no una respuesta exitosa del backend.

Cada request incluirá una breve descripción de sus precondiciones y scripts Postman para verificar invariantes estables: que el Gateway responda, que las operaciones exitosas tengan status 2xx y que las rutas excluidas no tengan éxito. No se fijarán códigos exactos para errores de negocio que dependen del estado de datos.

La colección no debe llamar directamente a MS4 Pagos. Para diagnosticar un `503`, se debe comprobar por separado si el destino de la ruta está levantado y, en los flujos de MS3, si sus dependencias servicio-a-servicio están disponibles.

## Ejecución local y diagnóstico

- Ejecutar la suite automatizada desde `Backend/APIGateWay` con `mvn clean verify`.
- Para Postman, iniciar primero las bases y microservicios requeridos siguiendo los README de cada servicio, y luego el Gateway. MS1 requiere MongoDB; MS3 requiere MySQL y los servicios que invoca para completar compra/pago/emisión.
- Importar la colección y environment; verificar que `gatewayUrl` apunta al puerto `8086` y completar únicamente IDs de datos locales.
- Un `404` en una ruta excluida es evidencia de que no hay ruta pública, no un fallo de conectividad; un `503` en una ruta pública indica que el Gateway no pudo conectarse con su destino o que el flujo dependiente no está disponible.
- Postman no demuestra CORS; usar la prueba automatizada de preflight para ese requisito.

## Alternativas consideradas

- **Solo probar con Postman:** insuficiente para detectar regresiones repetibles de todas las rutas y aislamiento interno.
- **Hacer depender la suite de todos los servicios y bases locales:** demasiado frágil para la ejecución normal del Gateway y para CI.
- **Agregar rutas de health o internas para facilitar la prueba:** cambia la API pública y debilita la allowlist; no se adopta.
