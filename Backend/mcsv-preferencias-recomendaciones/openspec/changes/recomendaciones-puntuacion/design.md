# Design

## Context

MS8 ya tiene Spring MVC, lectura de identidad, persistencia de preferencias y manejo centralizado de errores. El interceptor actual protege `/preferencias/me`; la nueva ruta debe usar la misma identidad sin cambiar el contrato existente. MS1 ofrece `/internal/eventos-publicados` con páginas `pagina`, `tamano`, `totalElementos`, `totalPaginas` y eventos con los campos que consume MS8. Compras aporta el historial del usuario y las co-compras mediante rutas internas autenticadas con `X-Service-Token`.

## Goals / Non-Goals

**Goals:**

- Integrar el nuevo endpoint con los componentes Spring MVC existentes y sin persistencia adicional.
- Mantener separado el fallo obligatorio del catálogo de MS1 (503) de las señales opcionales de Compras (degradación a preferencias).
- Hacer deterministas el cálculo, el filtrado temporal y el orden para que las pruebas no dependan del reloj real.
- Mantener pequeños y comprobables los clientes de MS1 y Compras, sin agregar dependencias externas.

**Non-Goals:**

- No cambiar endpoints ni contratos de MS1, Compras o preferencias.
- No guardar resultados ni añadir reintentos, concurrencia, caché o circuit breakers.

## Decisions

### Integración HTTP con MS1 y Compras

- Crear clientes tipados independientes para los dos servicios usando `RestClient`, como en MS1, con timeout de conexión de 2 segundos y lectura de 5 segundos.
- Configurar `EVENTOS_BASE_URL` con default host `http://localhost:8081` y valor Compose `http://lugares:8081`; configurar `COMPRAS_BASE_URL` con default host `http://localhost:8083` y valor Compose `http://compras:8083`.
- El cliente de MS1 solicitará páginas con `page` cero-based y `size=200`, y continuará hasta consumir `totalPaginas`. Un error HTTP, de conexión, timeout o lectura de la respuesta de MS1 se traducirá al error 503 estándar de MS8.
- El cliente de Compras enviará `X-Service-Token` en cada llamada con el valor de `SERVICIO_TOKEN`; no habrá un token útil predeterminado. Consultará primero `/internal/usuarios/{usuarioId}/historial-preferencias` y luego `/internal/eventos/{eventoId}/co-compras` solo para hasta los primeros cinco IDs distintos de `eventosComprados`, preservando el orden recibido. El historial entrega hasta 20 IDs distintos de eventos con compras confirmadas, ordenados desde la compra más reciente a la más antigua.
- Si falla cualquier consulta de Compras, se descartará completo el agregado de señales de compras para esa solicitud, incluso si otras consultas ya respondieron. Así nunca se mezclan puntuaciones parciales de historial/co-compra; el cálculo continúa con preferencias. Si no hay IDs comprados, no se invoca co-compras.

### Cálculo y orden de resultados

- Leer las preferencias con el servicio/repositorio existente; un cliente sin documento se representa con las listas vacías existentes y no crea datos.
- Aplicar una función común de comparación que recorta espacios y compara sin distinguir mayúsculas usando `Locale.ROOT`. Cada regla suma puntos como máximo una vez por evento, aunque haya varios participantes o varias co-compras coincidentes.
- El tipo del historial suma un punto por pertenencia a `tipos`, y un ID suma dos puntos por pertenencia a los resultados de co-compras. Los campos `cantidad` no se usan como multiplicadores: la consigna asigna puntos por coincidencia, no por volumen.
- Capturar el instante actual una vez por solicitud mediante `Clock`; comparar el estado sin distinguir mayúsculas e incluir solo `publicado` o `agotado` con `fechaHora` estrictamente posterior. Descartar puntuación cero, ordenar por puntos descendentes, fecha ascendente e ID ascendente y devolver los primeros 20.
- La respuesta es una lista directa con los campos del catálogo más `puntuacion`; si no hay candidatos puntuados, devolver lista vacía con 200. No se excluyen eventos ya comprados porque no es parte del alcance.

### API, configuración y compatibilidad

- Agregar `/recomendaciones/me` al patrón del interceptor de identidad existente y mantener la misma autorización 401/403. El controlador obtiene el `usuarioId` validado y delega en el servicio de recomendaciones.
- Mapear fallos de MS1 a 503 con el formato de error ya usado por MS8. Los fallos de Compras no se exponen al cliente; los errores de autorización conservan su comportamiento actual.
- Añadir a `application.yml` URLs por defecto host y propiedades de timeout; en Compose sobrescribir URLs a los nombres de servicio y mapear `SERVICIO_TOKEN` sin valor secreto incorporado. No se requiere modificar dependencias Maven.
- Ampliar el README con ejecución host/Docker, variables, endpoint y pesos. Explicar las simplificaciones: máximo 20 resultados sin paginación, solo cinco IDs comprados distintos para co-compras, cantidades ignoradas, fallback completo a preferencias ante cualquier fallo de Compras y ausencia de exclusión de eventos comprados.
- Agregar la operación a la colección Postman, reutilizando `baseUrl` y los headers de identidad.

### Estrategia de pruebas

- Probar en MVC 401 por ausencia de cualquiera de los headers, 403 por falta de `CLIENTE`, 200 para acceso autorizado y 503 cuando falla el cliente de MS1; incluir respuesta vacía autorizada.
- Probar el cálculo con cada peso, suma de reglas y normalización de texto; probar exclusión de estados no elegibles, eventos pasados y puntuación cero, orden estable y límite 20 con un reloj controlado.
- Probar paginación de MS1, query params y errores, y las rutas/headers/cuerpo de Compras con `MockRestServiceServer`. Cubrir historial sin compras, campo opcional `eventosComprados`, máximo 20 IDs distintos ordenados por fecha de confirmación, límite de cinco consultas de co-compras y degradación cuando falla el historial o cualquiera de las consultas de co-compras.

## Risks / Trade-offs

- [Las llamadas síncronas a MS1 y hasta cinco consultas de co-compras pueden aumentar la latencia] → Limitar cada operación a 2 s de conexión y 5 s de lectura, limitar co-compras a cinco IDs y omitir caché/reintentos en este alcance mínimo.
- [Sin `SERVICIO_TOKEN`, Compras probablemente rechazará las consultas] → No inventar una credencial; documentar la variable y conservar recomendaciones basadas en preferencias cuando falle Compras.
- [El catálogo completo se carga para filtrar y ordenar antes de devolver 20 resultados] → Mantener la lectura paginada por contrato y no agregar paginación a la respuesta pública, que está fuera de alcance.

## Migration Plan

No hay migración de datos. Desplegar MS8 con `EVENTOS_BASE_URL`, `COMPRAS_BASE_URL` y `SERVICIO_TOKEN` configurados para el entorno; verificar healthcheck y `GET /recomendaciones/me` con headers válidos. Si Compras todavía no acepta el token, el endpoint sigue funcionando con puntos de preferencias. Para revertir, retirar solo el cambio de MS8; no se alteran datos ni contratos de los otros servicios.
