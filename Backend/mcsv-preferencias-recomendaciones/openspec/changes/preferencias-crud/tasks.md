# Tasks

## 1. Crear la base del servicio

- [x] 1.1 Crear POM y Maven wrapper con Spring Boot 3.4.4, Java 21, dependencias de MS1 y Testcontainers MongoDB; verificar que `mvnw.cmd -version` identifica Java 21.
- [x] 1.2 Crear la aplicación `com.ticketya.preferencias` y `application.yml` con `PORT`, `MONGODB_URI`, Actuator y OpenAPI; verificar que el contexto Spring arranca con la configuración por defecto cuando MongoDB está disponible.

## 2. Implementar y probar el modelo de preferencias

- [x] 2.1 Crear el documento Mongo con `_id = usuarioId`, las tres listas y `actualizadoEn`, más su repositorio; verificar con Testcontainers MongoDB 8 que guarda, recupera, reemplaza y borra por usuario sin requerir transacciones.
- [x] 2.2 Implementar el reemplazo y la normalización de listas; agregar pruebas para recorte, listas vacías, textos vacíos, duplicados posteriores al recorte y actualización del timestamp, comprobando que una solicitud inválida no altera datos previos.

## 3. Exponer la API autenticada

- [ ] 3.1 Implementar lectura de `X-User-Id` y parsing de `X-User-Roles` separado por comas, recortado y sin distinguir mayúsculas; verificar `401` sin identidad y `403` sin rol `CLIENTE` en GET, PUT y DELETE.
- [ ] 3.2 Implementar `GET /preferencias/me`, `PUT /preferencias/me` y `DELETE /preferencias/me`; verificar con pruebas MVC respuestas `200` para lectura y reemplazo autorizados, `204` para eliminación, GET vacío con listas vacías y DELETE repetido idempotente.
- [ ] 3.3 Integrar validación de cuerpos y formato de error de MS1, y documentar rutas y respuestas en OpenAPI; verificar `400` ante listas inválidas y que `/v3/api-docs` publica los tres endpoints.

## 4. Preparar ejecución y uso

- [ ] 4.1 Crear Dockerfile multi-etapa con runtime Java 21 y usuario no root; agregar `.gitignore` y `.dockerignore`; crear Compose base con Mongo standalone en red privada sin `internal` ni `ports`, fuera de `ticketya-net`, y servicio preferencias en ambas redes con healthcheck Actuator y sin puertos publicados; verificar `docker compose config` y las redes/puertos resultantes.
- [ ] 4.2 Crear `docker-compose.dev.yml` con publicaciones `8089:8089` y `127.0.0.1:27018:27017`; verificar el Compose combinado y que la base no publica puertos en el Compose base.
- [ ] 4.3 Documentar en README ejecución host/Docker, creación de `ticketya-net`, Actuator, OpenAPI y variables `PORT`, `MONGODB_URI` y `SPRING_PROFILES_ACTIVE`; verificar que los comandos documentados coinciden con los archivos Compose.
- [ ] 4.4 Crear una colección Postman mínima para GET, PUT y DELETE con `X-User-Id`, `X-User-Roles` y base URL configurable; verificar que el JSON de colección se puede importar y que cada operación apunta a la ruta especificada.

## 5. Verificar el cambio completo

- [ ] 5.1 Ejecutar `mvnw.cmd test` con Docker disponible para validar pruebas MVC y persistencia Testcontainers.
- [ ] 5.2 Ejecutar `openspec validate --strict` desde el proyecto MS8 y corregir cualquier error o advertencia de los artefactos.
