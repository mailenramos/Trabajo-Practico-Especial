## 1. Completar las pruebas automatizadas

- [x] 1.1 Revisar la suite actual y separar explícitamente las pruebas unitarias de las pruebas de integración con backends falsos.
- [x] 1.2 Agregar pruebas unitarias aisladas para el filtro de indisponibilidad y la configuración CORS.
- [x] 1.3 Completar la matriz de rutas públicas para comprobar destino, método, path, query, body y headers, además de status y cuerpo de respuesta.
- [x] 1.4 Comprobar que rutas internas, desconocidas y métodos no permitidos no llegan a ningún backend; comprobar el caso de destino caído con HTTP 503.
- [x] 1.5 Ejecutar `mvn clean verify` sin iniciar microservicios, bases de datos o Docker.

## 2. Preparar pruebas manuales con Postman

- [x] 2.1 Crear la colección `Backend/APIGateWay/postman/TicketYa-Gateway.postman_collection.json` con requests de consultas públicas, identidad, reserva, compra/entradas y rutas excluidas.
- [x] 2.2 Crear un environment local con URL del Gateway y variables de usuario/IDs, sin credenciales ni datos sensibles.
- [x] 2.3 Agregar scripts de validación para status y las invariantes estables de cada request, capturando IDs solo cuando la respuesta lo permita.
- [x] 2.4 Asegurar que todos los requests de negocio apunten al Gateway y que ninguno invoque MS4 ni exponga rutas internas.

## 3. Documentar y ejecutar la verificación

- [x] 3.1 Documentar requisitos, orden de arranque, importación de colección/environment, variables a completar, precondiciones y diagnóstico de `404`/`503`.
- [x] 3.2 Aclarar que Postman no verifica CORS y describir la validación automatizada/de navegador correspondiente.
- [ ] 3.3 Ejecutar los escenarios manuales disponibles con servicios reales; registrar cuáles requieren datos o dependencias todavía no disponibles, sin presentar esos escenarios como aprobados.
- [x] 3.4 Validar la colección/environment en formato JSON y volver a ejecutar la suite automatizada después de los cambios.
