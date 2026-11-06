# Tasks

## 1. Estructura del servicio y persistencia

- [ ] 1.1 Preparar la estructura ejecutable del microservicio Spring Boot con `spring-boot-starter-data-mongodb`; verificar que la aplicación inicia con la configuración local documentada.
- [ ] 1.2 Configurar MongoDB en `docker-compose` para desarrollo local; verificar que el contenedor inicia y que la aplicación puede conectarse.
- [ ] 1.3 Crear el documento de lugar con `@Document` y sectores embebidos, y un repositorio basado en `MongoRepository`; verificar mediante pruebas unitarias con repositorio mockeado el mapeo del documento y las consultas.
- [ ] 1.4 Implementar generación de UUID String al crear cada sector y mantenerlo inmutable; verificar con pruebas unitarias que cada alta asigna un UUID único y que no cambia al actualizar el lugar.
- [ ] 1.5 Implementar el modelado de asientos numerados como pares derivados `(sectorId, fila, numero)` dentro de los rangos configurados, sin entidad ni repositorio de Asiento; verificar los límites e identidad con pruebas unitarias.
- [ ] 1.6 Agregar pruebas de integración con Testcontainers y MongoDB real para guardar y recuperar lugares con sectores embebidos; verificar que no se persisten documentos de asientos separados.

## 2. Casos de uso y API

- [ ] 2.1 Implementar registro de lugar y validación de nombre, dirección y coordenadas; verificar con pruebas de servicio los límites inclusivos y los valores inválidos.
- [ ] 2.2 Implementar alta de sectores numerados y no numerados, incluyendo las validaciones de dimensiones/capacidad; verificar con pruebas de servicio la configuración y los rechazos.
- [ ] 2.3 Implementar endpoints REST para crear lugares, agregar sectores, listar lugares y consultar un lugar con sus sectores; verificar contratos y códigos `201`, `200` y `404` mediante pruebas de API.
- [ ] 2.4 Implementar autorización de escritura con `X-User-Id` y `X-User-Role`; verificar respuestas `401` sin identidad y `403` para roles `cliente` u `organizador`, sin persistencia.
- [ ] 2.5 Implementar el mapeo de errores de validación e integridad a `400` y `409`; verificar que la respuesta no expone detalles internos de MongoDB.

## 3. Integración y documentación

- [ ] 3.1 Documentar las rutas, cuerpos, respuestas y requisitos de encabezados para el API Gateway; verificar que la documentación coincide con los endpoints implementados.
- [ ] 3.2 Ejecutar la suite del microservicio, incluyendo integración con MongoDB de Testcontainers, y revisar el flujo de extremo a extremo de registro y consulta de lugares y sectores; verificar todos los escenarios y códigos HTTP definidos en la especificación.

## 4. Entregables de la Entrega 1

- [ ] 4.1 Restringir la consulta de lugares y sectores a organizadores y administradores; verificar que los clientes reciben `403 Forbidden` y que las solicitudes GET sin encabezados reciben `401 Unauthorized`.
- [ ] 4.2 Documentar cada endpoint con OpenAPI/Swagger mediante springdoc, incluyendo método, ruta, parámetros, códigos de respuesta, ejemplos JSON y códigos de error; verificar que la especificación generada contiene esa información.
- [ ] 4.3 Agregar pruebas para la lista vacía con respuesta `200 OK` y para el alta de un sector en un lugar inexistente con respuesta `404 Not Found`; verificar que ambos casos pasan.
- [ ] 4.4 Cargar datos de prueba con al menos dos lugares que incluyan sectores numerados y no numerados; verificar que pueden consultarse desde el servicio.
- [ ] 4.5 Armar una colección Postman con los casos exitosos y de error de este cambio; verificar que la colección puede importarse y ejecutar sus solicitudes.
- [ ] 4.6 Escribir el README con los pasos para levantar MongoDB con Docker y ejecutar el servicio; verificar que los pasos documentados se pueden seguir desde un entorno limpio.
- [ ] 4.7 Incluir el diagrama del modelo de datos del servicio, mostrando la colección `lugares` con sus sectores embebidos; verificar que coincide con el modelo implementado.
