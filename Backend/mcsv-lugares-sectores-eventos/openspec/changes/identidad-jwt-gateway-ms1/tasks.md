# Tasks

- [x] 1. Adaptar la lectura de identidad para usar `X-User-Id` y parsear `X-User-Roles` como conjunto de roles separados por comas, sin precedencia arbitraria.
- [x] 2. Actualizar autorización de lugares y sectores: lectura autenticada para `ORGANIZADOR` o `ADMIN`, escritura solo para `ADMIN`; retirar la dependencia de `X-User-Role` y el alias legado `administrador`.
- [x] 3. Actualizar autorización del ciclo de vida de eventos: conservar `ORGANIZADOR` titular para crear/configurar/publicar y `ADMIN` para operaciones administrativas; mantener `/internal/...` sin autenticación de usuario.
- [x] 4. Hacer públicas búsqueda y consulta de detalle de eventos `publicado` o `agotado`; mantener el detalle de borrador autenticado y limitado al organizador titular o `ADMIN`, ocultándolo a otros usuarios.
- [x] 5. Agregar pruebas de API para rol insuficiente, recurso ajeno, usuario con varios roles, borrador consultado sin token, consultas públicas sin token y rutas internas sin identidad.
- [x] 6. Actualizar documentación de endpoints y contrato del Gateway para reflejar los headers, la visibilidad y las reglas de acceso; ejecutar las pruebas pertinentes de MS1.
