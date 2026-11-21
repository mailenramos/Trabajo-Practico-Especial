# Tasks

## 1. Errores HTTP

- [x] 1.1 Agregar pruebas MockMvc de `GET /ruta-que-no-existe` y de un método no permitido sobre una ruta existente; verificar respectivamente `404` y `405`, JSON estándar con `status` coincidente y `X-Correlation-Id` recibido o generado.
- [x] 1.2 Agregar prueba MockMvc para un `Content-Type` no soportado por un endpoint existente; verificar `415`, JSON estándar y correlación recibida o generada.
- [x] 1.3 Ajustar `ApiExceptionHandler` para mapear específicamente los errores de recurso estático inexistente y tipo de contenido no soportado; reutilizar el formato común, conservar el handler `405` existente y verificar las pruebas anteriores.
- [x] 1.4 Verificar con pruebas existentes o enfocadas que los errores de dominio y las excepciones inesperadas conservan sus códigos y que el `500` sigue siendo genérico.

## 2. Búsqueda pública

- [x] 2.1 Agregar una prueba MockMvc de integración con MongoDB mediante Testcontainers: persistir `BORRADOR`, `CANCELADO`, `FINALIZADO`, `PUBLICADO` y `AGOTADO`; consultar anónimamente `GET /eventos` y verificar que contenido y total incluyen solo los dos estados públicos.
- [x] 2.2 En la misma cobertura verificar la búsqueda con un filtro que coincida con el borrador y la repetición desde caché; comprobar que no se filtra el borrador. Mantener sin cambios la consulta de producción si las pruebas confirman el comportamiento observado.
- [x] 2.3 Ejecutar las pruebas MockMvc afectadas y las pruebas existentes de búsqueda y caché; comprobar que los tests de Mongo usan únicamente Testcontainers y reportar cualquier test omitido por falta de Docker.
